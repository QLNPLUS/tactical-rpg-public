package com.tactical.neoforge.client.ui.panel;

import com.tactical.neoforge.client.UiPalette;
import com.tactical.neoforge.client.BattleScreenInput;
import com.tactical.neoforge.client.UiZoom;
import com.tactical.neoforge.client.ui.debug.DevProbe;
import com.tactical.neoforge.client.ui.layout.Box;
import com.tactical.neoforge.client.ui.layout.Fingerprint;
import com.tactical.neoforge.client.ui.layout.HitTest;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.layout.UiEnvironmentKey;
import com.tactical.neoforge.client.ui.platform.ClientResourceGeneration;
import com.tactical.neoforge.client.ui.platform.Draw;
import com.tactical.neoforge.client.ui.screen.NativeTacticalScreen;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Slate;
import com.tactical.neoforge.client.ui.widget.PointerCapture;
import com.tactical.neoforge.client.ui.widget.Tooltip;
import com.tactical.neoforge.client.ui.widget.Tween;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.LanguageManager;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Base of the five replacing panels: one right-anchored panel that stands in for the command panel.
 *
 * <p>Everything the pages share lives here once - the layout cache, the panel surface with its hard
 * shadow, the 140ms swap entrance, hover and press tweens, one-shot pointer capture, the tooltip,
 * the probe and the input order (interactive HUD, then page controls, then the page's own world
 * strategy). A page only describes its own content and what a control does.</p>
 */
public abstract class NativePanelScreen extends NativeTacticalScreen {

    protected PanelLayout layout;
    protected final Tween tween = new Tween();
    protected final PointerCapture capture = new PointerCapture();
    protected long openedAt = Util.getMillis();
    protected long lastFrame = openedAt;
    protected String hoveredId;
    protected int mouseX;
    protected int mouseY;
    protected Rect tooltipBox = Rect.EMPTY;
    protected List<String> tooltipLines = List.of();
    private LayoutKey built;
    /** Development probe: how often this page has laid itself out, so a driver can see a rebuild. */
    private long rebuilds;

    // ---------------------------------------------------------------- layout

    /** Builds this page's content inside the shared skeleton. Called on init, resize and preset change. */
    protected abstract PanelLayout build(int guiWidth, int guiHeight, double scale, boolean narrow);

    /**
     * Everything this page's layout depends on besides the GUI size, the size preset and the
     * environment (language, font resources, saved preferences).
     *
     * <p>A page overrides this with a cheap fingerprint of its own content; the page is rebuilt only
     * when the fingerprint moves. Values that are merely drawn must stay out of it, so a changing
     * number redraws instead of relaying out.</p>
     */
    protected long contentFingerprint() {
        return 0L;
    }

    /**
     * Language, reloaded resources and every saved preference: a change in any of them can change
     * text metrics, so the cached layout has to be rebuilt even though the window did not move.
     *
     * <p>The resource generation is the real signal - a pack or language change can swap glyph
     * metrics inside the same {@code Font} object - and it is one counter read, not a per-frame scan.
     * Font identity and line height stay in the key as a cheap guard for a replaced font.</p>
     */
    private static long environmentFingerprint() {
        Minecraft minecraft = Minecraft.getInstance();
        LanguageManager languages = minecraft == null ? null : minecraft.getLanguageManager();
        Font font = minecraft == null ? null : minecraft.font;
        return UiEnvironmentKey.of(UiZoom.revision(), languages == null ? null : languages.getSelected(),
                ClientResourceGeneration.current(), font == null ? -1 : System.identityHashCode(font),
                font == null ? -1 : font.lineHeight);
    }

    /** Paints everything inside the panel, after the shared surface and before the tooltip. */
    protected abstract void drawBody(GuiGraphics graphics, Font font, double scale, double alpha, long now,
                                     double delta);

    /** What a control does when the pointer is pressed and released on it. */
    protected abstract void activate(String id);

    /** Return, cancel or close: the page's own back path, used by its button, right click and ESC. */
    protected abstract void back();

    /** Entrance length; the replacing panels switch with 140ms, not the command panel's 220ms. */
    protected int enterDuration() {
        return Motion.PANEL_SWAP_MS;
    }

    protected final void ensureLayout() {
        if (layout == null || !built.matches(currentKey())) rebuild();
    }

    /** GUI size, preset and the whole content fingerprint: everything a cached layout depends on. */
    private LayoutKey currentKey() {
        return new LayoutKey(width, height, UiZoom.zoom(),
                Fingerprint.mix(environmentFingerprint(), contentFingerprint()));
    }

    @Override
    protected void rebuild() {
        LayoutKey key = currentKey();
        layout = build(key.guiWidth(), key.guiHeight(), key.scale(),
                Scale.form(key.guiWidth(), key.guiHeight(), key.scale()) == Scale.Form.NARROW);
        built = key;
        rebuilds++;
        onRebuilt();
    }

    /**
     * Called once after every rebuild. The default drops state that belonged to the old layout: a
     * press whose row no longer exists must not turn the release into a click on whatever row took
     * its place, and a hover that named a gone row must not keep animating.
     */
    protected void onRebuilt() {
        capture.cancel();
        hoveredId = null;
    }

    protected final Rect panel() {
        return layout == null ? Rect.EMPTY : layout.panel();
    }

    protected final boolean narrow() {
        return layout != null && layout.narrow();
    }

    protected final double scale() {
        return layout == null ? 1.0D : layout.scale();
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void drawContent(GuiGraphics graphics, int pointerX, int pointerY, float partialTick) {
        ensureLayout();
        if (minecraft != null && !minecraft.isWindowActive()) capture.cancel();
        mouseX = pointerX;
        mouseY = pointerY;
        long now = Util.getMillis();
        double delta = Math.max(0.0D, Math.min(100.0D, now - lastFrame));
        lastFrame = now;
        Box hit = HitTest.topmost(layout.hitOrder(), pointerX, pointerY);
        // A page may own lines that are not controls but can still be shortened (a subtitle, an empty
        // state, a hint). They get the tooltip path, never a hit: passiveHover only names them.
        hoveredId = hit == null ? passiveHover(pointerX, pointerY) : hit.id();
        tween.hovered(hoveredId, now);

        double enter = Motion.easeOutCubic(Motion.progress(now - openedAt, enterDuration()));
        int rise = (int) Math.round(3.0D * (1.0D - enter));
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, rise, 0.0F);
        try {
            Rect panel = layout.panel();
            Draw.shadow(graphics, panel, Scale.PANEL_SHADOW, Slate.SHADOW, true);
            Draw.slab(graphics, panel, Slate.SLAB);
            drawBody(graphics, font, layout.scale(), enter, now, delta);
            // The battle HUD paints after this content, so the tooltip is drawn by renderTopOverlay instead
            // and only falls back to this pass when no HUD is drawn at all. Exactly one of them draws it.
            if (TooltipLayer.forFrame(hudHidden()) == TooltipLayer.CONTENT) {
                drawTooltip(graphics, font, now, enter);
            }
        } finally {
            graphics.pose().popPose();
        }
    }

    /**
     * The panel's tooltip, above the battle HUD.
     *
     * <p>Called once per frame by the HUD's own render hook, after the roster and the charge rail have
     * painted, so a portrait can never cover the text it overlaps. The unused tooltip fields are
     * published here too, which keeps the development probe in step with what was really drawn.</p>
     */
    @Override
    public void renderTopOverlay(GuiGraphics graphics, int pointerX, int pointerY, float partialTick) {
        if (TooltipLayer.forFrame(hudHidden()) != TooltipLayer.TOP_OVERLAY) return;
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, com.tactical.neoforge.client.ui.screen.TacticalSurface.TOP_OVERLAY_DEPTH);
        try {
            drawTooltip(graphics, font, Util.getMillis(), 1.0D);
        } finally {
            graphics.pose().popPose();
        }
    }

    /** True while the player has the whole HUD hidden, which also stops the top-overlay hook. */
    private static boolean hudHidden() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.options != null && minecraft.options.hideGui;
    }

    /** Draws the shared title row: one text line at the top of the panel's inner width. */
    protected final void drawTitle(GuiGraphics graphics, Font font, double scale, String text, double alpha) {
        Rect title = layout.rect(PanelLayout.TITLE);
        if (title.w() <= 0) return;
        Draw.scaledText(graphics, font, ellipsis(font, text, title.w()), title.x(), baseline(title, scale), scale,
                alpha(Slate.TEXT, alpha));
    }

    protected final void drawRule(GuiGraphics graphics) {
        Rect rule = layout.rect(PanelLayout.RULE);
        if (rule.w() > 0) Draw.accentRule(graphics, rule);
    }

    /** The shared Button: 14 high, small corner, centred label, hover, press and disabled states. */
    protected final void button(GuiGraphics graphics, Font font, double scale, String id, String label,
                                boolean enabled, double alpha, long now, double delta) {
        Rect rect = layout.rect(id);
        if (rect.w() <= 0) return;
        boolean hovered = enabled && id.equals(hoveredId);
        double progress = tween.progress(id, hovered, now, Motion.HOVER_MS, delta);
        boolean pressed = hovered && tween.pressActive(id, now);
        int base = !enabled ? Slate.SUNK : pressed ? Slate.SUNK : Draw.blend(Slate.RAISE, Slate.HOVER, progress);
        Draw.chip(graphics, rect, alpha(base, alpha));
        int pad = Scale.effectivePx(Motion.BUTTON_PAD_X, scale);
        String text = TextMetrics.iconOrEllipsis(label, Math.max(1, rect.w() - pad * 2), scale, font::width);
        int width = TextMetrics.width(font.width(text), scale);
        Draw.scaledText(graphics, font, text, rect.x() + (rect.w() - width) / 2,
                baseline(rect, scale) + (pressed ? 1 : 0), scale, alpha(enabled ? Slate.TEXT : Slate.TEXT3, alpha));
    }

    /**
     * A button's width at 100% for one real label: the text measured by the running font plus the shared
     * horizontal padding. The specification forbids giving a button a width borrowed from another
     * language, so every page asks this instead of quoting the Chinese label's four characters.
     */
    public static int buttonDesignWidth(String label) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft == null ? null : minecraft.font;
        int text = font == null || label == null ? 0 : font.width(label);
        return Motion.BUTTON_PAD_X * 2 + text;
    }

    /** One row of buttons keeps a single equal width: the widest of the labels it has to draw. */
    public static int buttonDesignWidthWidest(String... labels) {
        int widest = 0;
        for (String label : labels) widest = Math.max(widest, buttonDesignWidth(label));
        return widest;
    }

    /** One dialogs' worth of a row: filled small-corner surface with the specification's states. */    protected final Rect rowSurface(GuiGraphics graphics, String id, Rect rect, boolean enabled, double alpha,
                                    long now, double delta) {
        boolean hovered = enabled && id.equals(hoveredId);
        double progress = tween.progress(id, hovered, now, Motion.HOVER_MS, delta);
        boolean pressed = hovered && tween.pressActive(id, now);
        boolean lift = enabled && !narrow() && progress >= 0.5D;
        Rect box = rect.move(0, pressed ? 1 : lift ? -1 : 0);
        int base = !enabled ? Slate.RAISE : pressed ? Slate.SUNK : Draw.blend(Slate.RAISE, Slate.HOVER, progress);
        Draw.chip(graphics, box, alpha(base, alpha));
        return box;
    }

    /** A 16px icon well inside a row, with the accent condition the specification asks for. */
    protected final Rect iconWell(GuiGraphics graphics, Rect row, boolean highlighted, double alpha) {
        double scale = scale();
        Rect box = Rect.of(row.x() + Scale.effectivePx(Motion.ICON_BOX_INSET, scale),
                row.y() + Scale.effectivePx(Motion.ICON_BOX_INSET, scale),
                Scale.effectivePx(Motion.ICON, scale), Scale.effectivePx(Motion.ICON, scale));
        Draw.fill(graphics, box, alpha(highlighted ? UiPalette.accentDark() : Slate.SUNK, alpha));
        return box;
    }

    /** Renders an item stack inside a box, scaled to it; nothing is drawn for an empty stack. */
    protected static void item(GuiGraphics graphics, net.minecraft.world.item.ItemStack stack, Rect box) {
        if (stack == null || stack.isEmpty() || box.w() <= 0) return;
        graphics.pose().pushPose();
        graphics.pose().translate(box.x(), box.y(), 0.0F);
        float factor = box.w() / 16.0F;
        graphics.pose().scale(factor, factor, 1.0F);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
    }

    /** The single-line value row: left label in secondary text, right value, no background. */
    protected final void valueRow(GuiGraphics graphics, Font font, double scale, Rect rect, String label,
                                  String value, int valueColor, boolean labelSecondary, double alpha) {
        if (rect.w() <= 0) return;
        int y = baseline(rect, scale);
        if (label != null && !label.isEmpty()) {
            Draw.scaledText(graphics, font, label, rect.x(), y, scale,
                    alpha(labelSecondary ? Slate.TEXT2 : Slate.TEXT, alpha));
        }
        int valueWidth = value == null ? 0 : TextMetrics.width(font.width(value), scale);
        if (value != null && !value.isEmpty()) {
            Draw.scaledText(graphics, font, ellipsis(font, value, Math.max(1, rect.w() - valueWidth)), rect.right()
                    - valueWidth, y, scale, alpha(valueColor, alpha));
        }
    }

    protected final int textWidth(Font font, String text) {
        return TextMetrics.width(font.width(text == null ? "" : text), scale());
    }

    protected final String ellipsis(Font font, String text, int width) {
        return TextMetrics.ellipsis(text, width, scale(), font::width);
    }

    protected final List<String> wrap(Font font, String text, int width) {
        return TextMetrics.wrap(text, width, scale(), font::width);
    }

    protected static int baseline(Rect box, double textScale) {
        int line = (int) Math.round(Motion.LINE * textScale);
        return box.y() + Math.max(0, (box.h() - line) / 2);
    }

    protected static int alpha(int color, double value) {
        return Motion.withAlpha(color, value);
    }

    // ---------------------------------------------------------------- tooltip

    /**
     * A non-interactive region of the page that may still have to explain itself: a shortened line, an
     * empty state. Naming one here only brings up its tooltip - it never becomes clickable.
     */
    protected String passiveHover(double guiX, double guiY) {
        return null;
    }

    /**
     * Tooltip of the hovered control. A page declares the whole text it had to cut; the shared code
     * folds it, places it beside the control and never lets it cover the pointer.
     */
    protected String[] tooltipFor(String id) {
        return null;
    }

    private void drawTooltip(GuiGraphics graphics, Font font, long now, double alpha) {
        tooltipBox = Rect.EMPTY;
        tooltipLines = List.of();
        if (hoveredId == null || !tween.tooltipDue(now)) return;
        String[] text = tooltipFor(hoveredId);
        if (text == null || text[0] == null || text[0].isEmpty()) return;
        Rect trigger = layout.rect(hoveredId);
        if (trigger.w() <= 0) return;
        double scale = layout.scale();
        Tooltip.Content content = Tooltip.content(text[0], text.length > 1 ? text[1] : null, scale, font::width);
        if (content.isEmpty()) return;
        int boxWidth = Tooltip.width(content, scale, font::width);
        int boxHeight = Tooltip.height(content, scale);
        Rect box = Tooltip.place(trigger, boxWidth, boxHeight, width, height, mouseX, mouseY,
                Scale.effectivePx(Tooltip.GAP, scale));
        double fade = Motion.easeOutCubic(Motion.progress(now - tween.hoveredSince() - Motion.TOOLTIP_DELAY_MS,
                Motion.TOOLTIP_FADE_MS));
        Draw.shadow(graphics, box, Scale.SMALL_SHADOW, Motion.withAlpha(Slate.SHADOW_SM, fade), false);
        Draw.chip(graphics, box, Slate.FLOAT);
        int lineY = Tooltip.textTop(box, scale);
        int lineHeight = Scale.effectivePx(Motion.LINE, scale);
        java.util.List<String> drawn = new java.util.ArrayList<>();
        for (String line : content.primary()) {
            Draw.scaledText(graphics, font, line, Tooltip.textLeft(box, scale), lineY, scale,
                    Motion.withAlpha(Slate.TEXT, fade));
            drawn.add(line);
            lineY += lineHeight;
        }
        for (String line : content.secondary()) {
            Draw.scaledText(graphics, font, line, Tooltip.textLeft(box, scale), lineY, scale,
                    Motion.withAlpha(Slate.TEXT2, fade));
            drawn.add(line);
            lineY += lineHeight;
        }
        tooltipBox = box;
        tooltipLines = List.copyOf(drawn);
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double pointerX, double pointerY, int button) {
        ensureLayout();
        // Order: the interactive HUD, then this page's controls, then the page's own world strategy.
        if (super.mouseClicked(pointerX, pointerY, button)) return true;
        capture.cancel();
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            back();
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        Box hit = HitTest.topmost(layout.hitOrder(), pointerX, pointerY);
        if (hit != null) {
            capture.press(hit.id());
            tween.pressed(hit.id(), Util.getMillis());
            return true;
        }
        if (layout.panel().contains(pointerX, pointerY)) return true;
        return worldClick(pointerX, pointerY);
    }

    /**
     * A left click outside the panel. Most pages keep their original world policy, which is "nothing
     * happens"; only the aiming page hands the click to the battlefield.
     */
    protected boolean worldClick(double pointerX, double pointerY) {
        return true;
    }

    @Override
    public boolean mouseReleased(double pointerX, double pointerY, int button) {
        ensureLayout();
        if (minecraft != null && !minecraft.isWindowActive()) capture.cancel();
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        Box hit = HitTest.topmost(layout.hitOrder(), pointerX, pointerY);
        String id = capture.release(hit == null ? null : hit.id());
        if (id != null) activate(id);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) capture.cancel();
        if (BattleScreenInput.handleKey(this, keyCode, scanCode)) return true;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            back();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        capture.cancel();
        super.removed();
    }

    // ---------------------------------------------------------------- layout queries

    @Override
    public boolean overUi(double guiX, double guiY) {
        ensureLayout();
        return layout.panel().contains(guiX, guiY);
    }

    @Override
    public boolean consumesClick(double guiX, double guiY) {
        ensureLayout();
        return HitTest.topmost(layout.hitOrder(), guiX, guiY) != null;
    }

    // ---------------------------------------------------------------- probe

    @Override
    protected String probeJson() {
        ensureLayout();
        StringBuilder nodes = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Rect> entry : layout.allRects().entrySet()) {
            Rect rect = entry.getValue();
            if (rect.w() <= 0 || rect.h() <= 0) continue;
            if (!first) nodes.append(',');
            first = false;
            boolean interactive = layout.hitOrder().stream().anyMatch(box -> box.id().equals(entry.getKey()));
            boolean enabled = layout.hitOrder().stream()
                    .filter(box -> box.id().equals(entry.getKey()))
                    .findFirst().map(Box::enabled).orElse(true);
            nodes.append("{\"id\":\"").append(entry.getKey()).append("\",\"x\":").append(rect.x())
                    .append(",\"y\":").append(rect.y()).append(",\"w\":").append(rect.w())
                    .append(",\"h\":").append(rect.h()).append(",\"interactive\":").append(interactive)
                    .append(",\"enabled\":").append(enabled)
                    .append(",\"hovered\":").append(entry.getKey().equals(hoveredId)).append('}');
        }
        Rect panel = layout.panel();
        String tooltip = tooltipBox.w() <= 0 ? "null" : String.format(Locale.ROOT,
                "{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d,\"lines\":%d}", tooltipBox.x(), tooltipBox.y(),
                tooltipBox.w(), tooltipBox.h(), tooltipLines.size());
        String page = probeExtra();
        return String.format(Locale.ROOT,
                "{\"gui\":{\"w\":%d,\"h\":%d,\"scale\":%.3f,\"preset\":%d,\"narrow\":%b},"
                        + "\"environment\":{\"resourceGeneration\":%d,\"preferenceRevision\":%d,\"rebuilds\":%d},"
                        + "\"panel\":{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d},"
                        + "\"hovered\":\"%s\",\"tooltip\":%s%s,\"nodes\":[%s]}",
                width, height, layout.scale(), UiZoom.percent(), layout.narrow(),
                ClientResourceGeneration.current(), UiZoom.revision(), rebuilds,
                panel.x(), panel.y(), panel.w(), panel.h(), hoveredId == null ? "" : hoveredId, tooltip,
                page == null ? "" : ",\"page\":" + page, nodes);
    }

    /**
     * Extra read-only probe fields a page publishes about its own content - which rows are on it,
     * which of them are usable, where its own scroll sits. Development only, like the probe itself:
     * a driver has to be able to say what the page is showing without reading pixels.
     */
    protected String probeExtra() {
        return null;
    }

    /** Test hook: the current layout, or null before the first frame. */
    public PanelLayout layoutSnapshot() {
        ensureLayout();
        return layout;
    }
}
