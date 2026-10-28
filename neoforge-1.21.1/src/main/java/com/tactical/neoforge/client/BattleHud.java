package com.tactical.neoforge.client;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/** Stable, hover-only health roster. Charge order lives in {@link TurnChargeHud}. */
public final class BattleHud {
    public static final String PAGE = "tactical/overlays/battle_hud.html";
    private static final String HOVER_SOURCE = "health-names";
    private static Document document;
    private static long generation = -1;
    private static List<Integer> rosterIds = List.of();
    private static double lastScale = -1;
    private static int lastWidth = -1;
    private static int lastHeight = -1;
    private static double lastSafeBottom = -1;
    private static boolean lastJournalExpanded;
    private static int lastRosterCount = -1;

    private BattleHud() {}

    public static void tick() {
        if (!BattleClientState.active) { close(); return; }
        // Charge HUD must see dirty before this roster acknowledges the snapshot.
        TurnChargeHud.tick();
        if (document == null || document.isDisposed()) {
            document = ApricityUI.createDocument(PAGE);
            if (document == null) return;
            generation = -1;
            UiZoom.applyAll();
            WheelGuard.install(document);
        }
        boolean rebuilt = generation != document.getRefreshGeneration();
        if (rebuilt) {
            clearRosterHover();
            generation = document.getRefreshGeneration();
            rosterIds = List.of();
            lastScale = -1;
        }
        refreshScale();
        if (rebuilt || BattleClientState.dirty) {
            update();
            BattleClientState.dirty = false;
        }
        updateHoverHighlight();
    }

    public static void close() {
        clearRosterHover();
        if (document != null) ApricityUI.removeDocument(PAGE);
        document = null;
        generation = -1;
        rosterIds = List.of();
        lastScale = -1;
        TurnChargeHud.close();
    }

    /** UiZoom may call this immediately after a preset change. No DOM reconstruction. */
    public static void refreshScale() {
        TurnChargeHud.refreshScale();
        if (document == null || document.isDisposed()) return;
        double scale = UiZoom.zoom();
        int width = document.getViewport().layoutWidth();
        int viewportHeight = document.getViewport().layoutHeight();
        var mc = Minecraft.getInstance();
        double cssPerGui = (double) viewportHeight / Math.max(1, mc.getWindow().getGuiScaledHeight());
        double safeBottom = Math.max(68, 48 + mc.gui.getChat().getHeight()) * cssPerGui;
        boolean expanded = BattleLog.expandedPreference();
        int rosterCount = BattleClientState.participantHealth.size();
        if (lastScale == scale && lastWidth == width && lastHeight == viewportHeight
                && lastSafeBottom == safeBottom && lastJournalExpanded == expanded && lastRosterCount == rosterCount) return;
        lastJournalExpanded = expanded;
        lastRosterCount = rosterCount;
        lastScale = scale;
        lastWidth = width;
        lastHeight = viewportHeight;
        lastSafeBottom = safeBottom;
        Element root = document.getElementById("aui-scale-root");
        if (root != null) root.setAttribute("style", "--hud-font:" + px(13 * scale)
                + ";--hud-width:" + px(JournalUiLayout.hudWidth(scale, width))
                + ";line-height:" + px(18 * scale)
                + ";left:12px;top:12px;padding:" + px(13 * scale) + " " + px(16 * scale) + ";");
        setStyles(".hud-top", "padding-bottom",px(4*scale));
        setStyles(".health-row", "padding",px(3*scale)+" "+px(scale));
        setStyles(".health-line", "margin-bottom",px(2*scale));
        setStyles(".health-track", "height",px(5*scale));
        setStyles(".hud-footer", "padding-top",px(4*scale));
        setStyles(".hud-footer", "margin-top",px(4*scale));
        setStyles("#weapon", "font-size",px(11*scale));
        setStyles("#ap", "font-size",px(15*scale));
        var budget = JournalUiLayout.stack(scale, viewportHeight, (int) Math.ceil(safeBottom), expanded, rosterCount);
        if (root != null) {
            root.setAttribute("data-stack-layout", !budget.journalVisible() ? "journal-hidden"
                    : expanded && budget.journalLines() < 4 ? "reduced" : "expanded");
            root.setInlineStyleProperty("max-height", px(Math.max(0, viewportHeight - safeBottom - 24)));
            root.setInlineStyleProperty("overflow", "hidden");
        }
        setStyles("#health-roster", "max-height", px(budget.rosterHeight()));
        setStyles("#health-roster", "overflow-y", "auto");
        setStyles("#health-roster", "overflow-x", "hidden");
        // Explicit line metrics make the shared budget independent of page em defaults.
        setStyles(".health-line", "line-height", px(18 * scale));
        setStyles(".ap-line", "line-height", px(18 * scale));
        setStyles("#weapon", "line-height", px(16 * scale));
        setStyles("#weapon", "margin-top", px(4 * scale));
    }

    private static void setStyles(String selector,String property,String value) {
        for(Element e:document.querySelectorAll(selector))e.setInlineStyleProperty(property,value);
    }

    private static void update() {
        setText("round", Integer.toString(BattleClientState.round));
        setText("actor", BattleClientState.actor.isEmpty() ? "等待行动" : BattleClientState.actor);
        setText("ap", "AP " + BattleClientState.ap + " / " + BattleClientState.maxAp);
        setText("stance", BattleClientState.defending ? "防御中" : "回合恢复 +"+BattleClientState.apRecovery);
        setText("weapon", weaponText());
        List<Integer> ids = new ArrayList<>();
        for (int id : BattleClientState.turnEntityIds) {
            if (BattleClientState.participantHealth.containsKey(id) && !ids.contains(id)) ids.add(id);
        }
        for (int id : BattleClientState.participantHealth.keySet()) if (!ids.contains(id)) ids.add(id);
        Element list = document.getElementById("health-roster");
        if (list == null) return;
        // Membership changes only: HP packets never destroy a hovered name/listener.
        if (!ids.equals(rosterIds) || (ids.size() > 0 && document.getElementById("health-name-" + ids.getFirst()) == null)) {
            clearRosterHover();
            StringBuilder html = new StringBuilder();
            for (int id : ids) html.append("<div class=\"health-row\" id=\"health-row-").append(id)
                    .append("\"><div class=\"health-line\"><span class=\"health-name\" id=\"health-name-")
                    .append(id).append("\"></span><span class=\"health-number\" id=\"health-number-")
                    .append(id).append("\"></span></div><div class=\"health-track\"><div class=\"health-fill\" id=\"health-fill-")
                    .append(id).append("\"></div></div></div>");
            list.setInnerHTML(html.toString());
            rosterIds = List.copyOf(ids);
            lastScale=-1;refreshScale();
            for (int id : rosterIds) {
                Element name = document.getElementById("health-name-" + id);
                if (name == null) continue;
                name.addEventListener("mouseenter", event -> BattleEntityLocator.hover(HOVER_SOURCE, id));
                name.addEventListener("mouseleave", event -> BattleEntityLocator.clear(HOVER_SOURCE, id));
            }
        }
        for (int id : rosterIds) {
            int[] health = BattleClientState.participantHealth.get(id);
            int hp = Math.max(0, health[0]);
            int max = Math.max(1, health[1]);
            setText("health-name-" + id, nameFor(id));
            setText("health-number-" + id, hp + " / " + max);
            Element row = document.getElementById("health-row-" + id);
            String classes = "health-row" + (id == BattleClientState.actorEntityId ? " health-active" : "")
                    + (hp == 0 ? " health-dead" : "");
            if (row != null && !classes.equals(row.getClassName())) row.setClassName(classes);
            Element fill = document.getElementById("health-fill-" + id);
            String style = "width:" + Math.min(100, hp * 100L / max) + "%;";
            if (fill != null && !style.equals(fill.getAttribute("style"))) fill.setAttribute("style", style);
        }
    }

    private static void updateHoverHighlight() {
        int hovered = BattleEntityLocator.hoveredId();
        for (int id : rosterIds) {
            Element name = document.getElementById("health-name-" + id);
            if (name == null) continue;
            String classes = "health-name" + (id == hovered ? " health-hovered" : "");
            if (!classes.equals(name.getClassName())) name.setClassName(classes);
        }
    }

    private static void clearRosterHover() {
        BattleEntityLocator.clear(HOVER_SOURCE);
    }

    static String nameFor(int id) {
        var level = Minecraft.getInstance().level;
        Entity entity = level == null ? null : level.getEntity(id);
        if (entity != null) return entity.getDisplayName().getString();
        for (int i = 0; i < BattleClientState.turnEntityIds.length && i < BattleClientState.order.size(); i++)
            if (BattleClientState.turnEntityIds[i] == id) return BattleClientState.order.get(i);
        return "实体 #" + id;
    }

    private static String weaponText() {
        if (BattleClientState.weaponKind == 0) return BattleClientState.weapon + " · 近战";
        return BattleClientState.weapon + " · " + (BattleClientState.weaponKind == 2 ? "弩" : "弓")
                + " " + BattleClientState.weaponRange + " 格";
    }

    private static void setText(String id, String value) {
        Element element = document == null ? null : document.getElementById(id);
        if (element != null && !value.equals(element.getTextContent())) element.setTextContent(value);
    }

    static String px(double value) { return String.format(java.util.Locale.ROOT, "%.2fpx", value); }
}
