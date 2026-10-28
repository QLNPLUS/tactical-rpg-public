package com.tactical.neoforge.client;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.tactical.neoforge.Tactical;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Arrays;

/** One charge rail; AUI draws frames and Minecraft draws the actual participant models. */
@EventBusSubscriber(modid = Tactical.MOD_ID, value = Dist.CLIENT)
public final class TurnChargeHud {
    public static final String PAGE = "tactical/overlays/battle_turn_bar.html";
    private static final String HOVER_SOURCE = "turn-portraits";
    private static final ResourceLocation FRAME = frame("turn-portrait-frame.png");
    private static final ResourceLocation FRAME_CURRENT = frame("turn-portrait-frame-current.png");
    private static final ResourceLocation FRAME_HOVER = frame("turn-portrait-frame-hover.png");
    private static final ResourceLocation FRAME_CURRENT_HOVER = frame("turn-portrait-frame-current-hover.png");
    private static Document document;
    private static long generation = -1;
    private static int[] visibleIds = new int[0];
    private static double lastScale = -1;
    private static int lastWidth = -1;
    private static JournalUiLayout.Rail layout;

    private TurnChargeHud() { }

    public static void tick() {
        if (!BattleClientState.active) { close(); return; }
        if (document == null || document.isDisposed()) {
            document = ApricityUI.createDocument(PAGE);
            if (document == null) return;
            generation = -1;
            UiZoom.applyAll();
            WheelGuard.install(document);
        }
        if (generation != document.getRefreshGeneration()) {
            BattleEntityLocator.clear(HOVER_SOURCE);
            generation = document.getRefreshGeneration();
            visibleIds = new int[0];
            lastScale = -1;
        }
        refreshScale();
        // Hover can change without receiving a battle packet.
        update();
    }

    public static void close() {
        BattleEntityLocator.clear(HOVER_SOURCE);
        if (document != null) ApricityUI.removeDocument(PAGE);
        document = null;
        generation = -1;
        visibleIds = new int[0];
        lastScale = -1;
    }

    public static void refreshScale() {
        if (document == null || document.isDisposed()) return;
        double scale = UiZoom.zoom();
        int width = document.getViewport().layoutWidth();
        if (lastScale == scale && lastWidth == width) return;
        lastScale = scale;
        lastWidth = width;
        layout = JournalUiLayout.rail(scale, width);
        Element root = document.getElementById("charge-root");
        if (root != null) root.setAttribute("style", "left:" + BattleHud.px(layout.left())
                + ";top:12px;width:" + BattleHud.px(layout.width()) + ";");
        Element track = document.getElementById("charge-track");
        if (track != null) track.setAttribute("style", "left:" + BattleHud.px(layout.trackInset())
                + ";right:" + BattleHud.px(layout.trackInset()) + ";top:"
                + BattleHud.px(layout.top() + layout.portraitHeight() / 2) + ";");
    }

    private static TurnChargeRailLayout.Portrait[] placements() {
        return TurnChargeRailLayout.place(visibleIds, BattleClientState.gauges,
                BattleClientState.actorEntityId, layout.contentWidth(), layout.portraitWidth());
    }

    private static ResourceLocation frame(String name) {
        // AUI and Minecraft share exactly the same bundled texture, not a generated portrait.
        return ResourceLocation.fromNamespaceAndPath("apricityui", "apricity/tactical/textures/" + name);
    }

    private static void update() {
        Element portraits = document.getElementById("charge-portraits");
        if (portraits == null) return;
        int[] ids = BattleClientState.turnEntityIds;
        if (!Arrays.equals(visibleIds, ids) || (ids.length > 0 && document.getElementById("charge-portrait-" + ids[0]) == null)) {
            StringBuilder html = new StringBuilder();
            for (int id : ids) html.append("<div class=\"charge-portrait\" id=\"charge-portrait-")
                    .append(id).append("\"></div>");
            BattleEntityLocator.clear(HOVER_SOURCE);
            portraits.setInnerHTML(html.toString());
            visibleIds = ids.clone();
            for (int id : visibleIds) {
                Element portrait = document.getElementById("charge-portrait-" + id);
                if (portrait == null) continue;
                portrait.addEventListener("mouseenter", event -> BattleEntityLocator.hover(HOVER_SOURCE, id));
                portrait.addEventListener("mouseleave", event -> BattleEntityLocator.clear(HOVER_SOURCE, id));
            }
        }
        double height = layout.minimumHeight();
        for (var position : placements()) {
            Element portrait = document.getElementById("charge-portrait-" + position.entityId());
            if (portrait == null) continue;
            boolean current = position.entityId() == BattleClientState.actorEntityId;
            String style = "left:" + BattleHud.px(layout.inset() + position.left())
                    + ";top:" + BattleHud.px(layout.top() + position.top())
                    + ";width:" + BattleHud.px(layout.portraitWidth())
                    + ";height:" + BattleHud.px(layout.portraitHeight())
                    + ";z-index:" + (current ? 100 : position.lane() + 1) + ";";
            if (!style.equals(portrait.getAttribute("style"))) portrait.setAttribute("style", style);
            height = Math.max(height, layout.top() + position.top() + layout.portraitHeight() + layout.top());
        }
        Element root = document.getElementById("charge-root");
        if (root != null) root.setInlineStyleProperty("height", BattleHud.px(height));
        pollPortraitHover();
        int hovered = BattleEntityLocator.hoveredId();
        for (int id : visibleIds) {
            Element portrait = document.getElementById("charge-portrait-" + id);
            if (portrait == null) continue;
            String classes = "charge-portrait" + (id == BattleClientState.actorEntityId ? " charge-current" : "")
                    + (id == hovered ? " charge-hovered" : "");
            if (!classes.equals(portrait.getClassName())) portrait.setClassName(classes);
        }
    }

    /** Overlay dispatch can be consumed by another AUI document. Use the actual current
     * visible DOM rectangles, never rail index/entry order or native model hit boxes. */
    private static void pollPortraitHover() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.mouseHandler.isMouseGrabbed()
                || mc.screen != null && !(mc.screen instanceof TacticalScreen)) {
            BattleEntityLocator.clear(HOVER_SOURCE); return;
        }
        var pointer = document.screenToDocumentPosition(BattleUiRegions.cursor());
        int hit = -1, hitZ = -1;
        for (int id : visibleIds) {
            Element portrait = document.getElementById("charge-portrait-" + id);
            if (portrait == null || !portrait.isVisible || "none".equals(portrait.getComputedStyle().display)) continue;
            var r = portrait.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0 || pointer.x < r.left || pointer.x >= r.right
                    || pointer.y < r.top || pointer.y >= r.bottom) continue;
            int z = id == BattleClientState.actorEntityId ? 100 : 0;
            for (var placement : placements())
                if (placement.entityId() == id && z != 100) z = placement.lane() + 1;
            if (z >= hitZ) { hit = id; hitZ = z; }
        }
        if (hit < 0) BattleEntityLocator.clear(HOVER_SOURCE);
        else BattleEntityLocator.hover(HOVER_SOURCE, hit);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void renderHudPortraits(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && !mc.options.hideGui) renderPortraits(event.getGuiGraphics());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void renderScreenPortraits(ScreenEvent.Render.Post event) {
        if (Minecraft.getInstance().screen instanceof TacticalScreen) renderPortraits(event.getGuiGraphics());
    }

    private static void renderPortraits(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (!BattleClientState.active || document == null || document.isDisposed()
                || mc.level == null || mc.options.hideGui) return;
        float scale = document.getViewport().renderScale();
        int hovered = BattleEntityLocator.hoveredId();
        var ordered = placements();
        // Match AUI's real z-order, including tied participants and current-last.
        Arrays.sort(ordered, java.util.Comparator.comparingInt(p ->
                p.entityId() == BattleClientState.actorEntityId ? Integer.MAX_VALUE : p.lane()));
        for (var placement : ordered) {
            int id = placement.entityId();
            if (!(mc.level.getEntity(id) instanceof LivingEntity entity)) continue;
            Element portrait = document.getElementById("charge-portrait-" + id);
            if (portrait == null || !portrait.isVisible) continue;
            Element.DOMRect r = portrait.getBoundingClientRect();
            if (r.width < 1 || r.height < 1) continue;
            int left = (int) Math.round(r.left * scale), top = (int) Math.round(r.top * scale);
            int right = (int) Math.round(r.right * scale), bottom = (int) Math.round(r.bottom * scale);
            // The fully transparent opening is x5..31 / y5..37 in a 36x42 frame.
            int insetX = Math.max(1, (int) Math.ceil((right - left)
                    * JournalUiLayout.FRAME_INSET / JournalUiLayout.PORTRAIT_WIDTH));
            int insetY = Math.max(1, (int) Math.ceil((bottom - top)
                    * JournalUiLayout.FRAME_INSET / JournalUiLayout.PORTRAIT_HEIGHT));
            int innerLeft = left + insetX, innerTop = top + insetY;
            int innerRight = right - insetX, innerBottom = bottom - insetY;
            if (innerRight <= innerLeft || innerBottom <= innerTop) continue;
            float fit = Math.min((innerRight - innerLeft - 2) / Math.max(.5F, entity.getBbWidth()),
                    (innerBottom - innerTop - 2) / Math.max(.5F, entity.getBbHeight()));
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(0, 0, 500);
                // Repaint each opening as a unit: a later lane must cover earlier models,
                // not merely their AUI backgrounds. Never use baked entity screenshots.
                graphics.fill(innerLeft, innerTop, innerRight, innerBottom, 0xFF191A29);
                InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
                        innerLeft, innerTop, innerRight, innerBottom,
                        Math.max(1, (int) fit), 0.0F,
                        (innerLeft + innerRight) / 2.0F, (innerTop + innerBottom) / 2.0F, entity);
                boolean current = id == BattleClientState.actorEntityId;
                ResourceLocation texture = id == hovered
                        ? (current ? FRAME_CURRENT_HOVER : FRAME_HOVER)
                        : (current ? FRAME_CURRENT : FRAME);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                try {
                    graphics.blit(texture, left, top, right - left, bottom - top, 0.0F, 0.0F,
                            JournalUiLayout.FRAME_TEXTURE_WIDTH, JournalUiLayout.FRAME_TEXTURE_HEIGHT,
                            JournalUiLayout.FRAME_TEXTURE_WIDTH, JournalUiLayout.FRAME_TEXTURE_HEIGHT);
                } finally { RenderSystem.disableBlend(); }
            } finally { graphics.pose().popPose(); }
        }
    }
}
