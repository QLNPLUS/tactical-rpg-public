package com.tactical.neoforge.client;

import com.sighs.apricityui.dom.DocumentRegistry;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.tactical.neoforge.Tactical;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;

/**
 * Explicit CSS interface sizes, with direct pixel fonts and unchanged input coordinates.
 *
 * <p>Size presets intentionally reflow the layout instead of introducing a second
 * viewport transform. Buttons, native slots and geometry stay in one coordinate space.
 */
public final class UiZoom {
    public static final java.util.List<Integer> PRESETS = CssScale.PRESETS;
    public static final int MIN_PERCENT = CssScale.MIN_PERCENT;
    public static final int MAX_PERCENT = CssScale.MAX_PERCENT;
    private static final Map<Document, Long> WIRED = new WeakHashMap<>();
    private static int percent = 100;
    private static boolean loaded;
    private static boolean showRangeLegend;
    private static Properties preferences = new Properties();

    private UiZoom() {}

    public static double zoom() { load(); return percent / 100.0D; }
    public static boolean showRangeLegend() { load(); return showRangeLegend; }

    public static void toggleRangeLegend() {
        load();
        showRangeLegend = !showRangeLegend;
        applyAll();
        save();
    }
    public static int minPercent() { return MIN_PERCENT; }
    public static int maxPercent() { return MAX_PERCENT; }
    public static java.util.List<Integer> presets() { return PRESETS; }

    public static boolean owns(Document d) {
        return d != null && !d.isDisposed() && d.getPath() != null && d.getPath().startsWith("tactical/");
    }

    private static Path config() { return FMLPaths.CONFIGDIR.get().resolve("tactical-ui.properties"); }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (Files.isRegularFile(config())) {
                Properties p = new Properties();
                try (var in = Files.newInputStream(config())) { p.load(in); }
                preferences = p;
                percent = CssScale.readPercent(p);
                showRangeLegend = CssScale.readRangeLegend(p);
                if (!CssScale.VERSION.equals(p.getProperty("version"))) save();
            }
        } catch (Exception e) {
            Tactical.LOGGER.warn("[Tactical UI] cannot read interface size", e);
        }
    }

    public static void scale(double direction) { load(); setPercent(CssScale.adjacent(percent, direction)); }

    public static void reset() { setPercent(100); }

    /** Index into the new public presets, not the old three-step persistence format. */
    public static void setStep(int value) {
        setPercent(PRESETS.get(Math.max(0, Math.min(PRESETS.size() - 1, value))));
    }

    public static void setPercent(int value) {
        load();
        percent = CssScale.clamp(value);
        Tactical.LOGGER.info("[Tactical UI] interface size -> {}%", percent);
        applyAll();
        BattleHud.refreshScale();
        save();
    }

    private static void save() {
        try {
            Files.createDirectories(config().getParent());
            try (var out = Files.newOutputStream(config())) {
                preferences = CssScale.properties(preferences, percent, showRangeLegend);
                preferences.store(out, "Tactical UI preferences; viewport stays at 100%");
            }
        } catch (Exception e) {
            Tactical.LOGGER.warn("[Tactical UI] cannot save interface size", e);
        }
    }

    public static void tick() { applyAll(); }

    public static void applyAll() {
        load();
        for (Document d : DocumentRegistry.getAll()) {
            if (!owns(d)) continue;
            // Migrate zoom persisted by older tactical builds; presets now own sizing.
            resetViewport(d);
            long generation = d.getRefreshGeneration();
            if (WIRED.getOrDefault(d, -1L) != generation) {
                WIRED.put(d, generation);
                wire(d, "scale-minus", () -> scale(-1));
                wire(d, "scale-plus", () -> scale(1));
                wire(d, "scale-reset", UiZoom::reset);
                wire(d, "range-legend-toggle", UiZoom::toggleRangeLegend);
                wire(d, "inv-back", () -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.player != null) mc.player.closeContainer();
                    mc.setScreen(null);
                    BattleMenu.open();
                });
            }
            Element body = d.querySelector("body");
            if (body != null) {
                String current = body.getClassName();
                if (current == null) current = "";
                String base = current.replace("ui-small", "").replace("ui-large", "").trim();
                String target = base;
                if (!current.equals(target)) {
                    body.setClassName(target);
                    body.invalidateStyle();
                    d.markDirty(body, com.sighs.apricityui.render.Drawer.RELAYOUT);
                }
            }
            applyCss(d);
            Element label = d.getElementById("scale-value");
            String text = percent + "%";
            if (label != null && !text.equals(label.getTextContent())) label.setTextContent(text);
            Element legend = d.getElementById("range-legend-toggle");
            if (legend != null) {
                String legendText = "射程说明：" + (showRangeLegend ? "开启" : "关闭");
                if (!legendText.equals(legend.getTextContent())) legend.setTextContent(legendText);
                String pressed = Boolean.toString(showRangeLegend);
                if (!pressed.equals(legend.getAttribute("aria-pressed"))) legend.setAttribute("aria-pressed", pressed);
            }
        }
    }

    private static void applyCss(Document d) {
        double scale = zoom();
        Element body = d.querySelector("body");
        if (body == null) return;
        set(body, "--ui-font", CssScale.px(14, scale));
        // AUI 1.2.8 resolves em geometry against the HTML root's raw font-size,
        // without resolving var(). Body/root-panel fonts alone still yield 16px.
        Element html = d.querySelector("html");
        if (html != null) set(html, "font-size", CssScale.px(14, scale));
        set(body, "font-size", CssScale.px(14, scale));
        Element root = d.getElementById("aui-scale-root");
        if (root != null) set(root, "font-size", CssScale.px(14, scale));
        if (!BattleMenu.PAGE.equals(d.getPath())) {
            for (Element controls : d.querySelectorAll(".journal-controls")) {
                set(controls, "top", JournalUiLayout.controlsTop(scale, d.getViewport().layoutHeight()) + "px");
            }
        }
        set(body, "--hud-font", CssScale.px(13, scale));
        set(body, "--hud-width", CssScale.px(JournalUiLayout.HUD_WIDTH, scale));
        set(body, "--hud-line-height", CssScale.px(18, scale));
        set(body, "--log-font", CssScale.px(13, scale));
        set(body, "--log-line-height", CssScale.px(21, scale));
        // MenuLayout owns journal geometry in scaled pixels, including its separate
        // action scrollport. Auxiliary paper dialogs retain their original policy.
        if (d.getPath().startsWith("tactical/screens/")) {
            for (Element panel : d.querySelectorAll(".battle-menu,.facing-panel")) {
                boolean journal = panel.getClassName().contains("journal-menu");
                if (journal) {
                    applyMenuCss(d, panel, scale);
                } else {
                    set(panel, "max-height", Math.max(1, d.getViewport().layoutHeight() - 32) + "px");
                    set(panel, "overflow-y", "auto");
                    set(panel, "overflow-x", "hidden");
                    set(panel, "max-width", Math.max(1, d.getViewport().layoutWidth() - 40) + "px");
                    set(panel, "width", CssScale.px(304, scale));
                }
            }
        }
    }


    private static void applyMenuCss(Document d, Element book, double scale) {
        var geometry = MenuLayout.calculate(d.getViewport().layoutWidth(), d.getViewport().layoutHeight(), scale);
        set(book, "top", CssScale.px(geometry.inset(), 1));
        set(book, "right", CssScale.px(geometry.inset(), 1));
        set(book, "width", CssScale.px(geometry.width(), 1));
        set(book, "height", CssScale.px(geometry.height(), 1));
        set(book, "max-height", "none");
        set(book, "max-width", "none");
        set(book, "overflow-x", "visible");
        set(book, "overflow-y", "visible");
        clearFixedScroll(d, book);
        Element page = book.querySelector(".journal-page");
        if (page == null) return;
        set(page, "left", CssScale.px(geometry.pageLeft(), 1));
        set(page, "width", CssScale.px(geometry.pageWidth(), 1));
        set(page, "height", CssScale.px(geometry.height(), 1));
        set(page, "padding-top", CssScale.px(geometry.paddingY(), 1));
        set(page, "padding-bottom", CssScale.px(geometry.paddingY(), 1));
        set(page, "padding-left", CssScale.px(geometry.paddingX(), 1));
        set(page, "padding-right", CssScale.px(geometry.paddingX(), 1));
        set(page, "overflow-x", "hidden");
        set(page, "overflow-y", "hidden");
        clearFixedScroll(d, page);
        menuHeight(book, ".journal-heading", geometry.headingHeight());
        menuHeight(book, ".journal-ap", geometry.apHeight());
        menuHeight(book, ".journal-weapon", geometry.weaponHeight());
        menuHeight(book, ".journal-state", geometry.stateHeight());
        Element state = book.querySelector(".journal-state");
        if (state != null) set(state, "padding-top", CssScale.px(geometry.compact() ? 2 : 7, scale));
        Element actions = book.querySelector(".journal-actions");
        if (actions != null) {
            set(actions, "height", CssScale.px(geometry.actionsHeight(), 1));
            set(actions, "max-height", CssScale.px(geometry.actionsHeight(), 1));
            set(actions, "min-height", "0px");
            set(actions, "overflow-y", "auto");
            set(actions, "overflow-x", "hidden");
        }
        for (Element option : book.querySelectorAll(".journal-actions .option"))
            set(option, "height", CssScale.px(geometry.compact() ? 54 : 62, scale));
        for (Element description : book.querySelectorAll(".journal-actions .opt-desc"))
            set(description, "margin-top", CssScale.px(geometry.compact() ? 2 : 5.6, scale));
        var sideContent = geometry.sideContent(scale);
        Element side = book.querySelector(".journal-side");
        if (side != null) {
            set(side, "top", CssScale.px(geometry.sideTop(), 1));
            set(side, "width", CssScale.px(geometry.sideWidth(), 1));
            set(side, "height", CssScale.px(geometry.sideHeight(), 1));
            set(side, "box-sizing", "border-box");
            set(side, "padding-top", CssScale.px(sideContent.paddingTop(), 1));
            set(side, "padding-bottom", CssScale.px(sideContent.paddingBottom(), 1));
            // Text boxes are clipped individually; keep the decorative seal/ribbon
            // visible where it hangs below the leather panel.
            set(side, "overflow-x", "visible");
            set(side, "overflow-y", "visible");
            clearFixedScroll(d, side);
        }
        for (Element block : book.querySelectorAll(".side-block"))
            set(block, "margin-bottom", CssScale.px(sideContent.blockGap(), 1));
        // AUI 1.2.8 em is root-relative: local compression must set every text
        // metric in px, not merely shrink the leather panel's inherited font-size.
        for (Element label : book.querySelectorAll(".side-label")) {
            set(label, "font-size", CssScale.px(sideContent.fontSize(), 1));
            set(label, "margin-bottom", CssScale.px(sideContent.labelGap(), 1));
            set(label, "line-height", CssScale.px(sideContent.labelLineHeight(), 1));
            set(label, "height", CssScale.px(sideContent.labelLineHeight(), 1));
            set(label, "white-space", "nowrap");
            set(label, "overflow", "hidden");
        }
        for (Element value : book.querySelectorAll(".side-value,#stance")) {
            set(value, "font-size", CssScale.px(sideContent.fontSize(), 1));
            set(value, "line-height", CssScale.px(sideContent.valueLineHeight(), 1));
            set(value, "height", CssScale.px(sideContent.valueLineHeight(), 1));
            set(value, "white-space", "nowrap");
            set(value, "overflow", "hidden");
        }
        Element actor = book.querySelector("#actor");
        if (actor != null) {
            set(actor, "height", CssScale.px(sideContent.actorHeight(), 1));
            set(actor, "max-height", CssScale.px(sideContent.actorHeight(), 1));
            set(actor, "white-space", sideContent.actorLines() == 1 ? "nowrap" : "normal");
        }
        Element round = book.querySelector("#round");
        if (round != null) {
            set(round, "font-size", CssScale.px(sideContent.roundFontSize(), 1));
            set(round, "line-height", CssScale.px(sideContent.roundLineHeight(), 1));
            set(round, "height", CssScale.px(sideContent.roundLineHeight(), 1));
            set(round, "white-space", "nowrap");
            set(round, "overflow", "hidden");
        }
        Element stance = book.querySelector("#stance");
        if (stance != null) set(stance, "display", sideContent.showStance() ? "block" : "none");
        Element seal = book.querySelector(".journal-seal");
        if (seal != null) set(seal, "display", geometry.compact() ? "none" : "block");
        Element binding = book.querySelector(".journal-binding");
        if (binding != null) {
            set(binding, "left", CssScale.px(geometry.pageLeft() - 5 * scale, 1));
            set(binding, "top", CssScale.px(geometry.sideTop() - 8 * scale, 1));
            set(binding, "height", CssScale.px(geometry.sideHeight(), 1));
        }
        double gearTop = geometry.inset() + 7 + geometry.paddingY();
        for (Element gear : d.querySelectorAll(".journal-settings-toggle")) {
            set(gear, "top", CssScale.px(gearTop, 1));
            set(gear, "right", CssScale.px(geometry.inset() + 7 + geometry.paddingX(), 1));
        }
        for (Element controls : d.querySelectorAll(".journal-controls")) {
            set(controls, "top", CssScale.px(gearTop + 38 * scale, 1));
            set(controls, "right", CssScale.px(geometry.inset() + 7 + geometry.paddingX(), 1));
        }
    }

    private static void menuHeight(Element book, String selector, double height) {
        Element element = book.querySelector(selector);
        if (element != null) set(element, "height", CssScale.px(height, 1));
    }

    /** AUI eases setScrollTop; clear both public 1.2.8 render/target fields immediately. */
    private static void clearFixedScroll(Document d, Element element) {
        if (element.scrollTop == 0 && element.targetScrollTop == 0
                && element.scrollLeft == 0 && element.targetScrollLeft == 0) return;
        element.setScrollTop(0);
        element.setScrollLeft(0);
        element.scrollTop = element.targetScrollTop = 0;
        element.scrollLeft = element.targetScrollLeft = 0;
        d.markDirty(element, com.sighs.apricityui.render.Drawer.RELAYOUT);
    }

    private static void set(Element element, String property, String value) {
        if (!value.equals(element.getInlineStylePropertyValue(property)))
            element.setInlineStyleProperty(property, value);
    }

    /** Force one page back to an unscaled viewport, since sizing is CSS-only now. */
    private static void resetViewport(Document d) {
        try {
            if (Math.abs(d.getViewport().zoom() - 1.0D) > 0.001D) {
                d.setViewportZoom(1.0D);
                Tactical.LOGGER.info("[Tactical UI] reset stale viewport zoom on {}", d.getPath());
            }
        } catch (Throwable ignored) {
            // Page still building; the next tick retries.
        }
    }

    private static void wire(Document d, String id, Runnable action) {
        Element e = d.getElementById(id);
        if (e != null) e.addEventListener("click", event -> {
            event.preventDefault();
            event.stopPropagation();
            // Rebuilding the same DOM from inside its own dispatch invalidates the event.
            net.minecraft.client.Minecraft.getInstance().tell(action);
        });
    }
}
