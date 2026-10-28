package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.dom.DocumentRegistry;
import com.sighs.apricityui.screen.AuiLinkedScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ScreenEvent;
import java.util.Map;
import java.util.WeakHashMap;

/** Suppress AUI scroll defaults without interpreting the wheel as a UI setting. */
public final class WheelGuard {
    private static final Map<Document, Long> INSTALLED = new WeakHashMap<>();
    private WheelGuard() {}
    public static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (BattleScreenInput.scroll(event.getScrollDeltaY())) {
            event.setCanceled(true);
        } else if (!BattleUiRegions.overUi() && Minecraft.getInstance().screen instanceof AuiLinkedScreen screen
                && UiZoom.owns(screen.getLinkedDocument())) event.setCanceled(true);
    }
    public static void install(Document d) {
        if (d == null || d.isDisposed()) return;
        long generation = d.getRefreshGeneration();
        if (INSTALLED.getOrDefault(d, -1L) == generation) return;
        INSTALLED.put(d, generation);
        d.addEventListener("wheel", event -> {
            // Real panels own interface scrolling (notably journal history). Empty
            // screen space is the only place where tactical wheel routing wins.
            if (!BattleUiRegions.overUi()) {
                event.preventDefault(); event.stopPropagation();
            }
        }, true);
    }
    public static void tick() {
        for (Document d : DocumentRegistry.getAll()) if (UiZoom.owns(d)) install(d);
    }
}
