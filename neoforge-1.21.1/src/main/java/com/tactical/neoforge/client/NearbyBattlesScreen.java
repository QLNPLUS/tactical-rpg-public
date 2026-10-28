package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.tactical.neoforge.network.NearbyBattlesPacket;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.UUID;

/**
 * "Nearby battles" page: the unparticipated B entry.
 *
 * <p>Every row is one encounter in this player's own dimension with its manager, distance, roster
 * and an explicit join/refuse reason. The page itself decides nothing: joining sends only the row's
 * session id, and a refusal refreshes the rows so a stale entry cannot silently join elsewhere.
 * Dismissed rows stay hidden until they become joinable again.</p>
 */
public final class NearbyBattlesScreen extends TacticalScreen {

    public static final String PAGE = "tactical/screens/nearby_battles.html";
    /** Row slots the static page provides; the server caps its list at the same size. */
    public static final int ROWS = NearbyBattlesPacket.MAX_ENTRIES;

    private long generation = -1;
    private long renderedRevision = -1;

    private NearbyBattlesScreen() {
        super(PAGE, NearbyBattlesScreen::dismiss);
    }

    /** Open the page for a player who is not fighting; a fighter keeps the original B menu. */
    public static void open() {
        if (BattleClientState.active) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || !minecraft.player.isAlive()) return;
        // Never steal focus from an inventory, chat, death or another mod's screen.
        if (minecraft.screen != null && !(minecraft.screen instanceof TacticalScreen)) return;
        BattleDiscoveryClientState.undismissAll();
        BattleDiscoveryClientState.request();
        NearbyBattlesScreen screen = new NearbyBattlesScreen();
        screen.setPauseGame(false).setShowDefaultBackground(false);
        minecraft.setScreen(screen);
    }

    public static void tickOpenScreen() {
        if (Minecraft.getInstance().screen instanceof NearbyBattlesScreen screen) screen.bind();
    }

    /** Closing never joins anything: the list is simply forgotten until the next B press. */
    public static void close() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.screen instanceof NearbyBattlesScreen) minecraft.setScreen(null);
    }

    private static void dismiss() {
        close();
    }

    @Override
    protected void init() {
        super.init();
        bind();
    }

    private void bind() {
        Document document = getLinkedDocument();
        if (document == null) return;
        if (generation != document.getRefreshGeneration()) {
            generation = document.getRefreshGeneration();
            renderedRevision = -1;
            wire(document);
        }
        if (renderedRevision != BattleDiscoveryClientState.revision()) {
            renderedRevision = BattleDiscoveryClientState.revision();
            fill(document);
        }
    }

    private void wire(Document document) {
        for (int index = 0; index < ROWS; index++) {
            final int slot = index;
            var join = document.getElementById("row-" + index + "-join");
            if (join != null) {
                join.addEventListener("click", event -> {
                    event.preventDefault();
                    event.stopPropagation();
                    Minecraft.getInstance().tell(() -> join(slot));
                });
            }
            var dismiss = document.getElementById("row-" + index + "-dismiss");
            if (dismiss != null) {
                dismiss.addEventListener("click", event -> {
                    event.preventDefault();
                    event.stopPropagation();
                    Minecraft.getInstance().tell(() -> dismiss(slot));
                });
            }
        }
        var refresh = document.getElementById("nearby-refresh");
        if (refresh != null) {
            refresh.addEventListener("click", event -> {
                event.preventDefault();
                event.stopPropagation();
                Minecraft.getInstance().tell(BattleDiscoveryClientState::request);
            });
        }
        var close = document.getElementById("nearby-close");
        if (close != null) {
            close.addEventListener("click", event -> {
                event.preventDefault();
                event.stopPropagation();
                Minecraft.getInstance().tell(NearbyBattlesScreen::close);
            });
        }
    }

    private void fill(Document document) {
        List<NearbyBattlesPacket.Entry> entries = BattleDiscoveryClientState.visibleEntries();
        for (int index = 0; index < ROWS; index++) {
            var row = document.getElementById("row-" + index);
            if (row == null) continue;
            NearbyBattlesPacket.Entry entry = index < entries.size() ? entries.get(index) : null;
            if (entry == null) {
                // A row that holds nothing must not keep the text or an apparently usable button of
                // the encounter that used to be here.
                row.setAttribute("data-slot", "empty");
                clearRow(document, index);
                continue;
            }
            row.setAttribute("data-slot", "filled");
            setText(document, "row-" + index + "-name", manager(entry));
            setText(document, "row-" + index + "-meta",
                    entry.distance() + " 格 · 参战 " + entry.members() + "/" + entry.capacity());
            setText(document, "row-" + index + "-reason",
                    entry.joinable() ? "可以加入" : entry.reason());
            var join = document.getElementById("row-" + index + "-join");
            if (join != null) join.setAttribute("aria-disabled", Boolean.toString(!entry.joinable()));
        }
        var empty = document.getElementById("nearby-empty");
        if (empty != null) empty.setAttribute("data-state", entries.isEmpty() ? "visible" : "hidden");
        // Always show the real state: refreshing, waiting for the rate limit, or the last refusal.
        // The last refusal and the refresh state are shown together: a player who was just told why a
        // join failed must not lose that reason the moment a refresh starts.
        String last = BattleDiscoveryClientState.lastMessage();
        String message;
        if (BattleDiscoveryClientState.awaiting()) {
            message = reason(last, "正在刷新附近的战斗…", " · 正在刷新附近的战斗…");
        } else if (BattleDiscoveryClientState.refreshPending()) {
            message = reason(last, "刷新过于频繁，稍后会自动重试", " · 稍后会自动重试");
        } else {
            message = last == null ? "" : last;
        }
        setText(document, "nearby-message", message);
    }

    /** Keep a real refusal reason visible next to the refresh state instead of replacing it. */
    private static String reason(String last, String refreshOnly, String suffix) {
        return last == null || last.isBlank() ? refreshOnly : last + suffix;
    }

    private static String manager(NearbyBattlesPacket.Entry entry) {
        return entry.manager().isBlank() ? "未知管理者" : entry.manager();
    }

    /** Blank one row completely, so no stale encounter (or its button) stays on screen. */
    private static void clearRow(Document document, int index) {
        setText(document, "row-" + index + "-name", "");
        setText(document, "row-" + index + "-meta", "");
        setText(document, "row-" + index + "-reason", "");
        var join = document.getElementById("row-" + index + "-join");
        if (join != null) join.setAttribute("aria-disabled", "true");
    }

    private static void setText(Document document, String id, String text) {
        var element = document.getElementById(id);
        if (element != null) element.setTextContent(text == null ? "" : text);
    }

    private void join(int slot) {
        NearbyBattlesPacket.Entry entry = BattleDiscoveryClientState.rowAt(slot);
        if (entry == null) {
            // The row is gone (dismissed or refreshed away): refresh instead of acting on a stale slot.
            BattleDiscoveryClientState.request();
            return;
        }
        if (!entry.joinable()) {
            // Never guess another encounter; simply refresh so the reason is current.
            BattleDiscoveryClientState.request();
            return;
        }
        BattleDiscoveryClientState.joinRow(slot);
    }

    private void dismiss(int slot) {
        BattleDiscoveryClientState.dismissRow(slot);
    }
}
