package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.style.Animation;
import com.tactical.neoforge.Tactical;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One bounded-panel entrance per document generation, sampled by AUI on render frames.
 *
 * <p>The entrance state is carried in the panel's inline style, but only through the element's own
 * declaration API. An earlier version appended and removed the raw substring {@code ";opacity:0;"};
 * AUI 1.2.8 re-serializes the whole style attribute on every declaration write (UiZoom sets the menu
 * root font-size once per tick, for one), which rewrites that substring as {@code "opacity: 0;"} and
 * makes the literal removal a no-op. The panel then stayed at opacity 0 permanently while AUI still
 * reported it visible and hit-testable, so clicks landed on an invisible grid. Declaration-level
 * writes cannot be orphaned by a re-serialization.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID, value = Dist.CLIENT)
public final class WindowEntrance {
    private static final String NAME = "tactical-window-enter";
    private static final String OPACITY = "opacity";
    private static final String ANIMATION = "animation";
    private static final String HIDDEN_OPACITY = "0";
    private static final String ANIMATION_SPEC = NAME + " " + EntranceTiming.DURATION_MS + "ms ease-out 1 both";
    private static final Map<Document, Entrance> ENTRANCES = new WeakHashMap<>();
    private static boolean registered;

    private WindowEntrance() { }

    /** Prepare invisibly; don't spend the animation's first frames on initial layout/font work. */
    public static void install(Document document) {
        if (!UiZoom.owns(document)) return;
        long generation = document.getRefreshGeneration();
        Entrance prior = ENTRANCES.get(document);
        if (!needsEntrance(prior, generation)) return;
        var windows = document.querySelectorAll("[data-window-entrance]");
        if (windows.isEmpty()) return; // Async DOM creation: retry on the next frame.
        List<Element> panels = new ArrayList<>();
        for (Element window : windows) {
            // Opacity uses a full-window FBO in AUI 1.2.8, even for a small panel.
            // Never add that compositing group to body/html or a viewport-sized wrapper.
            if (window == document.body || window == document.documentElement) continue;
            panels.add(window);
            hide(window);
        }
        ENTRANCES.put(document, new Entrance(generation, panels));
    }

    /** One entrance per generation: repeated render frames must not re-hide a released panel. */
    static boolean needsEntrance(Entrance prior, long generation) {
        return prior == null || prior.generation != generation;
    }

    /** Holds the panel back through the declaration API, so any later style write keeps it. */
    static void hide(Element panel) {
        panel.setInlineStyleProperty(OPACITY, HIDDEN_OPACITY);
    }

    /** Releases the panel: only the opacity declaration goes, every other declaration survives. */
    static void show(Element panel) {
        panel.removeInlineStyleProperty(OPACITY);
    }

    static void animate(Element panel) {
        panel.setInlineStyleProperty(ANIMATION, ANIMATION_SPEC);
    }

    static void clearAnimation(Element panel) {
        panel.removeInlineStyleProperty(ANIMATION);
    }

    @SubscribeEvent
    public static void beforeFrame(RenderFrameEvent.Pre event) {
        long now = System.nanoTime();
        ENTRANCES.entrySet().removeIf(entry -> entry.getKey().isDisposed());
        for (Document document : Document.getAll()) {
            install(document);
            Entrance entrance = ENTRANCES.get(document);
            if (entrance == null || entrance.done) continue;
            if (entrance.started != 0) {
                entrance.timing.frame(now);
                if (now - entrance.started >= EntranceTiming.DURATION_MS * 1_000_000L) {
                    for (Element panel : entrance.panels) clearAnimation(panel);
                    entrance.done = true; // Drop the filled animation spec, avoiding permanent motion/FBO work.
                    entrance.panels.clear();
                    entrance.bounds = null;
                    if (Boolean.getBoolean("tactical.ui.entranceTelemetry")) {
                        Tactical.LOGGER.info("[Tactical entrance] {} frames={} meanMs={} maxMs={} over25ms={}",
                                document.getPath(), entrance.timing.samples(), entrance.timing.meanMs(),
                                entrance.timing.worstMs(), entrance.timing.over25ms());
                    }
                }
                continue;
            }
            if (document.needsInitialFullCommit() || document.isInitialCommitSlicing()) continue;
            // Require the same committed panel bounds on two successive render frames.
            List<Bounds> bounds = new ArrayList<>();
            boolean ready = true;
            var viewport = document.getViewport();
            for (Element panel : entrance.panels) {
                var rect = panel.getBoundingClientRect();
                if (rect.width <= 0 || rect.height <= 0) ready = false;
                if (rect.width >= viewport.layoutWidth() && rect.height >= viewport.layoutHeight()) {
                    for (Element candidate : entrance.panels) show(candidate);
                    entrance.done = true;
                    break;
                }
                bounds.add(new Bounds(rect.x, rect.y, rect.width, rect.height));
            }
            if (entrance.done) continue;
            if (!ready || !bounds.equals(entrance.bounds)) {
                entrance.bounds = bounds;
                continue;
            }
            if (!registered) {
                // Opacity only: even translate triggers geometry/hit-test recommits in 1.2.8.
                // Native items have a separate rendering path; never scale their ancestor.
                Animation.registerKeyframe(NAME, 0, Map.of("opacity", "0"));
                Animation.registerKeyframe(NAME, 100, Map.of("opacity", "1"));
                registered = true;
            }
            for (Element panel : entrance.panels) {
                show(panel);
                animate(panel);
            }
            entrance.started = now;
            entrance.timing.frame(now);
        }
    }

    private record Bounds(double x, double y, double width, double height) { }

    /** Package-private so the regression test can drive the real generation guard. */
    static final class Entrance {
        final long generation;
        final List<Element> panels;
        final EntranceTiming timing = new EntranceTiming();
        List<Bounds> bounds;
        long started;
        boolean done;

        Entrance(long generation, List<Element> panels) {
            this.generation = generation;
            this.panels = panels;
        }
    }
}
