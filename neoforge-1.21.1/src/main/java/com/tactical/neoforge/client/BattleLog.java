package com.tactical.neoforge.client;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.event.MouseEvent;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.parser.HTML;
import com.sighs.apricityui.screen.AuiLinkedScreen;
import com.sighs.apricityui.style.Text;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.BattleLogPacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Bottom-left battle journal: button-only clicks, whole expanded panel wheel browsing. */
@EventBusSubscriber(modid = Tactical.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BattleLog {
    public static final String PAGE = "tactical/overlays/battle_log.html";
    private static final BattleLogState STATE = new BattleLogState();
    private static Document document;
    private static ClientLevel level;
    private static long renderedGeneration = -1;
    private static long wiredGeneration = -1;
    private static BattleLogLayout.Placement placement;
    private static double renderedScale = -1;
    private static final LogHoverAnimation HOVER = new LogHoverAnimation();

    static boolean expandedPreference() { return STATE.visible() && !STATE.collapsed(); }

    /** Refuse this encounter's traffic from the membership whose exit the server just published. */
    public static void retire(java.util.UUID session, long generation) {
        STATE.retire(session, generation);
    }

    /** Life-cycle boundary: the journal forgets every encounter, including its refusals. */
    public static void disconnect() {
        STATE.disconnect();
    }

    private BattleLog() { }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try (InputStream stream = BattleLog.class.getResourceAsStream("/assets/apricityui/apricity/" + PAGE)) {
                if (stream != null) HTML.putTemple(PAGE, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                else Tactical.LOGGER.warn("[Tactical] missing bundled battle journal page");
            } catch (IOException error) {
                Tactical.LOGGER.error("[Tactical] could not load battle journal page", error);
            }
        });
    }

    /** Called only by the clientbound payload's enqueueWork handler. */
    public static void accept(BattleLogPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) return;
        // A log - including its CLEAR - belongs to one encounter; the current one wins over a stale one.
        // Only the membership this client currently shows may write into its journal. A row published
        // for an earlier generation of the same encounter is refused like any other stale packet.
        if (!BattleMembership.matches(packet.membershipServerEpoch(), packet.membershipGeneration(),
                packet.battleId())) return;
        syncLevel(minecraft);
        if (STATE.accept(packet, Util.getMillis()) && !STATE.visible()) close();
    }

    private static void syncLevel(Minecraft minecraft) {
        if (level == minecraft.level) return;
        STATE.clear();
        close();
        level = minecraft.level;
    }

    private static boolean screenHidden(Minecraft minecraft) {
        if (minecraft.options.hideGui || minecraft.screen instanceof ChatScreen
                || minecraft.screen instanceof AbstractContainerScreen<?>) return true;
        if (minecraft.screen instanceof AuiLinkedScreen linked) {
            Document linkedDocument = linked.getLinkedDocument();
            return linkedDocument != null && "tactical/screens/battle_inventory.html".equals(linkedDocument.getPath());
        }
        // Other vanilla screens (pause/options/etc.) should keep their own input and layout.
        return minecraft.screen != null && !(minecraft.screen instanceof TacticalScreen);
    }

    private static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        syncLevel(minecraft);
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
            STATE.clear();
            close();
            return;
        }
        if (!STATE.visible()) {
            close();
            return;
        }
        if (screenHidden(minecraft)) {
            hide();
            return;
        }
        if (document == null || document.isDisposed()) {
            if (HTML.getTemple(PAGE) == null) return;
            document = ApricityUI.createDocument(PAGE);
            renderedGeneration = wiredGeneration = -1;
            placement = null;
            if (document == null) return;
        }
        long generation = document.getRefreshGeneration();
        if (wiredGeneration != generation) {
            Element toggle = document.getElementById("battle-log-toggle");
            if (toggle == null) return;
            // AUI dispatches these directly from Minecraft MouseHandler. Do not consume page/root hits.
            for (String type : List.of("mousedown", "mouseup")) {
                toggle.addEventListener(type, event -> {
                    if (event instanceof MouseEvent mouse) mouse.consumeNative();
                    event.stopPropagation();
                });
            }
            toggle.addEventListener("click", event -> {
                event.preventDefault();
                event.stopPropagation();
                if (event instanceof MouseEvent mouse && mouse.button != 0) return;
                if (STATE.toggleCollapsed()) tick();
            });
            wiredGeneration = generation;
        }
        BattleLogLayout.Placement next = calculatePlacement(minecraft);
        if (STATE.dirty() || renderedGeneration != generation || renderedScale != UiZoom.zoom()
                || !next.equals(placement)) {
            if (update(next)) {
                renderedGeneration = generation;
                placement = next;
                renderedScale = UiZoom.zoom();
                STATE.markClean();
            }
        } else {
            // Returning from chat/container does not reconstruct the journal or reset collapse.
            setShown(next.visible());
        }
        WindowEntrance.install(document);
        animateHover();
    }

    private static BattleLogLayout.Placement calculatePlacement(Minecraft minecraft) {
        var viewport = document.getViewport();
        double cssPerGui = (double) viewport.layoutHeight() / Math.max(1, minecraft.getWindow().getGuiScaledHeight());
        // Vanilla chat is drawn above y=height-40. Its configured unfocused height can be much larger
        // than the hotbar/health HUD, so reserve both even when there are no recent chat messages.
        int safeBottom = (int) Math.ceil(Math.max(68, 48 + minecraft.gui.getChat().getHeight()) * cssPerGui);
        int hudBottom = 0;
        for (Document hudDocument : ApricityUI.getDocument("tactical/overlays/battle_hud.html")) {
            if (hudDocument.isDisposed()) continue;
            Element hud = hudDocument.getElementById("aui-scale-root");
            if (hud == null) continue;
            var rect = hud.getBoundingClientRect();
            if (rect.height <= 0) continue;
            Position guiBottom = hudDocument.documentToScreenPosition(new Position(rect.x, rect.y + rect.height));
            hudBottom = Math.max(hudBottom, (int) Math.ceil(document.screenToDocumentPosition(guiBottom).y));
        }
        double scale = UiZoom.zoom();
        var budget = JournalUiLayout.stack(scale, viewport.layoutHeight(), safeBottom,
                expandedPreference(), BattleClientState.participantHealth.size());
        // First layout and both overlays use exactly the same roster/journal reservation.
        if (BattleClientState.active) hudBottom = Math.max(hudBottom, budget.hudBottom());
        var metrics = CssScale.journal(scale);
        var wider = new CssScale.JournalMetrics(metrics.headerHeight(), metrics.bodyChrome(), metrics.rowHeight(),
                JournalUiLayout.journalWidth(scale, viewport.layoutWidth()));
        return BattleLogLayout.place(viewport.layoutWidth(), viewport.layoutHeight(), hudBottom,
                safeBottom, STATE.collapsed(), wider);
    }

    /** Whole visible panel, independent of CSS pointer-events (non-button clicks still pass through). */
    public static boolean containsAt(Position guiPosition) {
        Minecraft minecraft = Minecraft.getInstance();
        if (guiPosition == null || !STATE.visible() || screenHidden(minecraft)
                || document == null || document.isDisposed() || placement == null || !placement.visible()) return false;
        Element panel = document.getElementById("battle-log-panel");
        if (panel == null) return false;
        String style = panel.getAttribute("style");
        if (style == null || style.contains("display:none")) return false;
        Position point = document.screenToDocumentPosition(guiPosition);
        var rect = panel.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0 && point.x >= rect.x && point.x < rect.x + rect.width
                && point.y >= rect.y && point.y < rect.y + rect.height;
    }

    /** Called once by the parent native wheel bridge, never by a second DOM wheel listener. */
    public static boolean scrollAt(Position guiPosition, double delta) {
        if (!containsAt(guiPosition) || STATE.collapsed() || placement.lines() == 0) return false;
        STATE.setVisibleLines(placement.lines());
        if (!STATE.scroll(delta)) return false;
        tick();
        return true;
    }

    /** Used only for clicks by the tactical native-input bridge; body clicks remain transparent. */
    public static boolean interceptsAt(Position guiPosition) {
        if (!containsAt(guiPosition)) return false;
        Element toggle = document.getElementById("battle-log-toggle");
        Element target = document.hitTest(document.screenToDocumentPosition(guiPosition));
        return toggle != null && target != null && toggle.contains(target);
    }

    private static boolean update(BattleLogLayout.Placement next) {
        Element panel = document.getElementById("battle-log-panel");
        Element entries = document.getElementById("battle-log-entries");
        Element status = document.getElementById("battle-log-status");
        Element count = document.getElementById("battle-log-count");
        Element toggle = document.getElementById("battle-log-toggle");
        if (panel == null || entries == null || status == null || count == null || toggle == null) return false;
        boolean compact = STATE.collapsed() || next.lines() == 0;
        panel.setClassName(compact ? "battle-log is-collapsed" : "battle-log");
        // CSS sizing stays in one coordinate space. Preserve the generation's entrance animation.
        String style = "left:" + next.left() + "px;top:" + next.top() + "px;width:" + next.width()
                + "px;height:" + next.height() + "px;display:" + (next.visible() ? "block" : "none") + ";";
        String oldStyle = panel.getAttribute("style");
        int animation = oldStyle == null ? -1 : oldStyle.indexOf("animation:");
        if (animation >= 0) style += oldStyle.substring(animation);
        panel.setAttribute("style", style);
        toggle.setTextContent(compact ? "展开 +" : "收起 −");
        toggle.setAttribute("aria-expanded", Boolean.toString(!compact));
        toggle.setAttribute("aria-label", compact ? "展开战斗记录" : "收起战斗记录");
        // Automatic compact mode preserves the browsing offset and explicit preference.
        if (!compact) STATE.setVisibleLines(next.lines());
        var visible = STATE.visibleHistory();
        status.setTextContent(STATE.historyOffset() == 0 ? "最新 · 滚轮查看历史" : "历史 · 向下滚动返回最新");
        String range = visible.isEmpty() ? "0" : visible.getFirst().sequence() + "–" + visible.getLast().sequence();
        count.setTextContent(range + " / " + STATE.sequence() + " 条");
        resetHover();
        StringBuilder html = new StringBuilder();
        for (BattleLogPacket.Entry entry : visible) {
            String category = category(entry.category());
            html.append("<div class=\"log-entry log-").append(category).append("\">")
                    .append("<span class=\"log-label\">").append(label(category)).append("</span>")
                    .append("<span class=\"log-text\" data-log-sequence=\"").append(entry.sequence()).append("\">")
                    .append("<span class=\"log-text-inner\">").append(escape(entry.text())).append("</span></span></div>");
        }
        entries.setInnerHTML(html.toString());
        panel.setAttribute("data-log-layout", !next.visible() ? "hidden" : compact ? "header-only"
                : next.lines() < 4 ? "reduced" : "expanded");
        if (!STATE.collapsed() && next.lines() < 4)
            status.setTextContent(next.lines() == 0 ? "窗口过矮 · 仅显示标题" : "窗口过矮 · 减少可见行数");
        applyScale();
        return true;
    }

    /** Inline actual text nodes: root inheritance alone loses to the page's fixed px rules. */
    private static void applyScale() {
        double scale = UiZoom.zoom();
        scaleStyle(".battle-log", "font-size", 13, scale);
        scaleStyle(".battle-log", "padding-top", 13, scale);
        scaleStyle(".battle-log", "padding-bottom", 13, scale);
        scaleStyle(".battle-log", "padding-left", 16, scale);
        scaleStyle(".battle-log", "padding-right", 16, scale);
        scaleStyle(".log-header", "height", 28, scale);
        scaleStyle(".log-title", "font-size", 14, scale);
        scaleStyle(".log-title", "line-height", 21, scale);
        scaleStyle("#battle-log-toggle", "font-size", 12, scale);
        scaleStyle("#battle-log-toggle", "line-height", 18, scale);
        scaleStyle("#battle-log-toggle", "height", 26, scale);
        scaleStyle("#battle-log-toggle", "padding-top", 2, scale);
        scaleStyle("#battle-log-toggle", "padding-bottom", 2, scale);
        scaleStyle("#battle-log-toggle", "padding-left", 7, scale);
        scaleStyle("#battle-log-toggle", "padding-right", 7, scale);
        scaleStyle("#battle-log-toggle", "min-width", 54, scale);
        scaleStyle(".log-details", "padding-top", 3, scale);
        scaleStyle(".log-entry", "height", CssScale.journal(scale).rowHeight(), 1);
        scaleStyle(".log-entry,.log-label,.log-text,.log-text-inner", "line-height", CssScale.journal(scale).rowHeight(), 1);
        scaleStyle(".log-label", "width", 32, scale);
        scaleStyle(".log-label", "font-size", 11, scale);
        scaleStyle(".log-text,.log-text-inner", "font-size", 13, scale);
        scaleStyle(".log-status", "height", 22, scale);
        scaleStyle(".log-status", "padding-top", 4, scale);
        scaleStyle(".log-status,.log-status span", "font-size", 10, scale);
        scaleStyle(".log-status,.log-status span", "line-height", 15, scale);
    }

    /** Uses the real native cursor, not a CSS class/pseudo state. Text remains pointer-transparent. */
    private static void animateHover() {
        if (document == null || document.isDisposed()) { HOVER.reset(); return; }
        Minecraft minecraft = Minecraft.getInstance();
        if (!STATE.visible() || STATE.collapsed() || placement == null || !placement.visible()
                || placement.lines() == 0 || screenHidden(minecraft) || minecraft.mouseHandler.isMouseGrabbed()) {
            resetHover();
            return;
        }
        Element panel = document.getElementById("battle-log-panel");
        if (panel == null || panel.getAttribute("style") == null
                || panel.getAttribute("style").contains("display:none")) { resetHover(); return; }
        Position mouse = document.getMouseDocumentPosition();
        Element hovered = null;
        double overflow = 0;
        for (Element clip : document.querySelectorAll(".log-text")) {
            Element inner = clip.querySelector(".log-text-inner");
            if (inner == null) continue;
            // Same font-mode, fallback chain, size, weight and letter spacing as AUI's renderer.
            double measured = Text.measureText(inner, inner.getTextContent());
            setStyle(inner, "width", BattleHud.px(Math.ceil(measured)));
            var rect = clip.getBoundingClientRect();
            double available = Math.max(0, clip.getClientWidth());
            if (mouse != null && available > 0 && rect.height > 0 && measured > available + .5
                    && mouse.x >= rect.x && mouse.x < rect.x + Math.min(rect.width, available)
                    && mouse.y >= rect.y && mouse.y < rect.y + rect.height) {
                hovered = clip;
                overflow = measured - available;
            }
        }
        double offset = HOVER.offset(hovered == null ? null : hovered.getAttribute("data-log-sequence"),
                overflow, UiZoom.zoom(), Util.getMillis());
        for (Element clip : document.querySelectorAll(".log-text")) {
            Element inner = clip.querySelector(".log-text-inner");
            if (inner != null) setStyle(inner, "left", BattleHud.px(clip == hovered ? -offset : 0));
        }
    }

    private static void resetHover() {
        HOVER.reset();
        if (document == null || document.isDisposed()) return;
        for (Element inner : document.querySelectorAll(".log-text-inner")) setStyle(inner, "left", "0px");
    }

    private static void setStyle(Element element, String property, String value) {
        if (!value.equals(element.getInlineStylePropertyValue(property)))
            element.setInlineStyleProperty(property, value);
    }

    private static void scaleStyle(String selector, String property, double base, double scale) {
        String value = CssScale.px(base, scale);
        for (Element element : document.querySelectorAll(selector)) {
            if (!value.equals(element.getInlineStylePropertyValue(property)))
                element.setInlineStyleProperty(property, value);
        }
    }

    private static String category(String value) {
        return switch (value) {
            case "battle", "turn", "move", "attack", "damage", "heal", "effect", "death",
                    "block", "reject", "enemy", "inventory", "action" -> value;
            default -> "action";
        };
    }

    private static String label(String category) {
        return switch (category) {
            case "battle" -> "战斗";
            case "turn" -> "回合";
            case "move" -> "移动";
            case "attack" -> "攻击";
            case "damage" -> "伤害";
            case "heal" -> "治疗";
            case "effect" -> "状态";
            case "death" -> "击败";
            case "block" -> "格挡";
            case "reject" -> "提示";
            case "enemy" -> "敌情";
            case "inventory" -> "物品";
            default -> "行动";
        };
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void setShown(boolean shown) {
        if (document == null || document.isDisposed()) return;
        Element panel = document.getElementById("battle-log-panel");
        if (panel == null) return;
        String value = panel.getAttribute("style");
        if (value == null) return;
        String target = value.replace("display:block", shown ? "display:block" : "display:none")
                .replace("display:none", shown ? "display:block" : "display:none");
        if (!value.equals(target)) panel.setAttribute("style", target);
    }

    private static void hide() { resetHover(); setShown(false); }

    private static void close() {
        resetHover();
        if (document != null) ApricityUI.removeDocument(PAGE);
        document = null;
        renderedGeneration = wiredGeneration = -1;
        placement = null;
    }

    @EventBusSubscriber(modid = Tactical.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() { }

        @SubscribeEvent
        public static void onTick(ClientTickEvent.Post event) { tick(); }

        @SubscribeEvent
        public static void onGuiFrame(RenderGuiEvent.Pre event) { animateHover(); }

        @SubscribeEvent
        public static void onScreenOpening(ScreenEvent.Opening event) {
            // Screen init can render before the next tick; suppress the overlay immediately.
            hide();
        }

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            STATE.disconnect();
            close();
            level = null;
        }
    }
}
