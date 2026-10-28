package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.tactical.neoforge.network.DefendPacket;
import com.tactical.neoforge.network.EndTurnPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Mouse-driven command panel shown automatically at the start of each player
 * turn. It lives on the right edge of the screen so the battlefield stays
 * visible behind it.
 */
public final class BattleMenu {

    public static final String PAGE = "tactical/screens/battle_menu.html";

    private static final java.util.Map<Document, Long> WIRED = new java.util.WeakHashMap<>();
    private static boolean dismissed;
    private static boolean pendingReopen;
    private static int lastAction = -1;
    private static int lastTurn = -1;
    private static int lastWindowWidth = -1;
    private static int lastWindowHeight = -1;
    private static String renderedWeapon="";
    private static int renderedKind=-1,renderedRange=-1;
    private static int renderedAp=-1,renderedAttack=-1,renderedItem=-1,renderedPlace=-1,renderedRecovery=-1,renderedPaid=-1;

    private BattleMenu() {
    }

    /** Called every client tick; opens the panel once per decision point. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!BattleClientState.active) {
            closeOwnMenu(minecraft);
            reset();
            return;
        }
        int width = minecraft.getWindow().getWidth();
        int height = minecraft.getWindow().getHeight();
        if (lastWindowWidth != -1 && (width != lastWindowWidth || height != lastWindowHeight)) {
            closeOwnMenu(minecraft);
            BattleClientState.mode = BattleMode.NONE;
            dismissed = false;
        }
        lastWindowWidth = width;
        lastWindowHeight = height;
        if (lastTurn != BattleClientState.round) {
            lastTurn = BattleClientState.round;
            dismissed = false;
        }
        if (!BattleClientState.isPlayerTurn()) {
            closeOwnMenu(minecraft);
            dismissed = false;
            pendingReopen = false;
            lastAction = BattleClientState.actionCount;
            return;
        }
        if (minecraft.screen instanceof TacticalScreen screen && screen.getLinkedDocument() != null
                && PAGE.equals(screen.getLinkedDocument().getPath())) {
            Document document = screen.getLinkedDocument();
            if (WIRED.getOrDefault(document, -1L) != document.getRefreshGeneration()) {
                fill(document); wire(document, minecraft);
            } else if(!renderedWeapon.equals(BattleClientState.weapon)||renderedKind!=BattleClientState.weaponKind||renderedRange!=BattleClientState.weaponRange
                    ||renderedAp!=BattleClientState.ap||renderedAttack!=BattleClientState.attackCost
                    ||renderedItem!=BattleClientState.itemCost||renderedPlace!=BattleClientState.placeCost
                    ||renderedRecovery!=BattleClientState.apRecovery||renderedPaid!=BattleClientState.paidActionCount) {
                fill(document);
            }
            updateVitals(document);
        }
        if (minecraft.screen != null) return;
        if (BattleClientState.moving) {
            pendingReopen = true;
            return;
        }
        if (pendingReopen) {
            pendingReopen = false;
            dismissed = false;
        }
        if (lastAction != BattleClientState.actionCount) {
            lastAction = BattleClientState.actionCount;
            dismissed = false;
        }
        if (!dismissed) {
            open();
        }
    }

    /**
     * True only for the screen this class owns.
     *
     * <p>Every AUI page is a {@link TacticalScreen}, so "close the tactical screen" also closes the
     * nearby list, the journal or any other tactical page the player opened for a reason of their own.
     * The battle menu may only ever close itself.</p>
     */
    public static boolean owns(Document document) {
        return document != null && ownsPath(document.getPath());
    }

    /** The identity comparison behind {@link #owns}, kept pure so it can be tested headlessly. */
    public static boolean ownsPath(String path) {
        return PAGE.equals(path);
    }

    /** Close the battle menu if - and only if - it is the screen currently open. */
    private static void closeOwnMenu(Minecraft minecraft) {
        if (!(minecraft.screen instanceof TacticalScreen screen)) return;
        if (!owns(screen.getLinkedDocument())) return;
        minecraft.setScreen(null);
    }

    public static void reset() {
        dismissed = false;
        pendingReopen = false;
        lastAction = -1;
        lastTurn = -1;
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!BattleClientState.active || minecraft.screen != null) {
            return;
        }
        dismissed = true;
        // Opening the panel always drops any pending world action.
        BattleClientState.mode = BattleMode.NONE;
        BattleClientState.selectedSlot = -1;
        TacticalScreen screen = new TacticalScreen(PAGE, () -> {
            BattleClientState.mode = BattleMode.NONE;
            minecraft.setScreen(null);
        });
        screen.setPauseGame(false).setShowDefaultBackground(false);
        minecraft.setScreen(screen);
        Document document = screen.getLinkedDocument();
        if (document == null) {
            return;
        }
        fill(document);
        wire(document, minecraft);
    }

    private static void fill(Document document) {
        renderedWeapon=BattleClientState.weapon;renderedKind=BattleClientState.weaponKind;renderedRange=BattleClientState.weaponRange;
        set(document, "round", Integer.toString(BattleClientState.round));
        set(document, "actor", BattleClientState.actor.isEmpty() ? "等待" : BattleClientState.actor + " 的回合");
        set(document, "ap", BattleClientState.ap + " / " + BattleClientState.maxAp);
        set(document, "weapon", BattleClientState.weaponKind == 0
                ? BattleClientState.weapon + " · 近战"
                : BattleClientState.weapon + " · 射程 " + BattleClientState.weaponRange);
        renderedAp=BattleClientState.ap;renderedAttack=BattleClientState.attackCost;renderedItem=BattleClientState.itemCost;
        renderedPlace=BattleClientState.placeCost;renderedRecovery=BattleClientState.apRecovery;renderedPaid=BattleClientState.paidActionCount;
        set(document, "stance", BattleClientState.defending ? "防御中" : "下回合 +"+BattleClientState.apRecovery+" AP");
        set(document, "attack-cost", BattleClientState.attackCost + " AP");
        set(document, "attack-desc", "选择射程内的敌人");
        set(document, "tool-cost", BattleClientState.itemCost + " / " + BattleClientState.placeCost + " AP");
        set(document, "tool-desc", "使用主手道具 · E 背包");
        set(document, "skill-cost", "按技能计费");
        set(document, "defend-cost", "25 AP");
        set(document, "flee-cost", com.tactical.core.combat.BattleRules.FLEE_COST + " AP");
        set(document, "recovery", "+" + BattleClientState.apRecovery + " AP");
        updateVitals(document);
        set(document,"end-desc","保留剩余 AP");
        set(document, "status", "操作 "+BattleClientState.paidActionCount+" 次 · 连续涨费");
        renderGauges(document);
    }

    /** Resource packets may change without a new AP quote or weapon change. */
    private static void updateVitals(Document document) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            int[] health = BattleClientState.participantHealth.get(player.getId());
            set(document, "hp", health != null
                    ? Math.max(0, health[0]) + "/" + Math.max(1, health[1])
                    : SkillClientState.number(player.getHealth()) + "/" + SkillClientState.number(player.getMaxHealth()));
        }
        set(document, "mp", SkillClientState.number(BattleClientState.mp) + "/"
                + SkillClientState.number(BattleClientState.maxMp));
    }
    private static void renderGauges(Document document) {
        Element list = document.getElementById("gauges");
        if (list == null) {
            return;
        }
        list.setInnerHTML("");
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < BattleClientState.order.size(); i++) {
            String name = escape(BattleClientState.order.get(i));
            int percent = i < BattleClientState.gauges.length ? BattleClientState.gauges[i] : 0;
            html.append("<div class=\"menu-gauge\">")
                    .append("<span class=\"gauge-name\">").append(name).append("</span>")
                    .append("<span class=\"gauge-track\"><span class=\"gauge-fill\" style=\"width:")
                    .append(percent).append("%\"></span></span>")
                    .append("<span class=\"gauge-pct\">").append(percent).append("%</span>")
                    .append("</div>");
        }
        list.setInnerHTML(html.toString());
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void wire(Document document, Minecraft minecraft) {
        WIRED.put(document, document.getRefreshGeneration());
        setSettingsOpen(document, false);
        Element settings = document.getElementById("menu-settings");
        if (settings != null) settings.addEventListener("click", event -> {
            event.preventDefault();
            event.stopPropagation();
            minecraft.tell(() -> setSettingsOpen(document,
                    !"true".equals(settings.getAttribute("aria-expanded"))));
        });
        Element close = document.getElementById("menu-close");
        if (close != null) close.addEventListener("click", ignored -> minecraft.tell(() -> {
            dismissed = true;
            BattleClientState.mode = BattleMode.NONE;
            minecraft.setScreen(null);
        }));
        Element move = document.getElementById("opt-move");
        if (move != null) {
            move.addEventListener("click", ignored -> {
                BattleClientState.mode = BattleMode.MOVE;
                BattleAim.open();
            });
        }

        Element skill = document.getElementById("opt-skill");
        if (skill != null) skill.addEventListener("click", ignored -> SkillMenu.open());

        Element attack = document.getElementById("opt-attack");
        if (attack != null) {
            attack.addEventListener("click", ignored -> {
                BattleClientState.mode = BattleMode.ATTACK;
                BattleAim.open();
            });
        }

        Element defend = document.getElementById("opt-defend");
        if (defend != null) {
            defend.addEventListener("click", ignored -> {
                BattleClientState.mode = BattleMode.NONE;
                minecraft.setScreen(null);
                BattleFacing.openDefend();
            });
        }

        Element item = document.getElementById("opt-tool");
        if (item != null) {
            item.addEventListener("click", ignored -> BattleTool.open());
        }

        Element flee=document.getElementById("opt-flee");
        if(flee!=null)flee.addEventListener("click",event->{
            var stamp=BattleClientState.stamp();
            if(stamp==null)return;
            PacketDistributor.sendToServer(new com.tactical.neoforge.network.FleePacket(stamp));
            BattleClientState.mode=BattleMode.NONE;minecraft.setScreen(null);
        });

        Element endTurn = document.getElementById("opt-end");
        if (endTurn != null) {
            endTurn.addEventListener("click", ignored -> {
                minecraft.setScreen(null);
                BattleFacing.open();
            });
        }
    }

    /** Escape dismisses settings first without leaving the current battle menu. */
    static boolean closeSettings(Document document) {
        if (document == null || !PAGE.equals(document.getPath())) return false;
        Element button = document.getElementById("menu-settings");
        if (button == null || !"true".equals(button.getAttribute("aria-expanded"))) return false;
        setSettingsOpen(document, false);
        return true;
    }

    private static void setSettingsOpen(Document document, boolean open) {
        Element button = document.getElementById("menu-settings");
        Element controls = document.getElementById("journal-settings");
        if (button == null || controls == null || document.isDisposed()) return;
        button.setAttribute("aria-expanded", Boolean.toString(open));
        controls.setAttribute("aria-hidden", Boolean.toString(!open));
        button.setClassName("journal-settings-toggle" + (open ? " is-open" : ""));
        controls.setClassName("journal-controls" + (open ? " is-open" : ""));
        button.invalidateStyle();
        controls.invalidateStyle();
        document.markDirty(controls, com.sighs.apricityui.render.Drawer.RELAYOUT);
    }

    private static void set(Document document, String id, String value) {
        Element element = document.getElementById(id);
        if (element != null && !value.equals(element.getTextContent())) {
            element.setTextContent(value);
        }
    }
}
