package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.screen.ApricityContainerScreen;
import net.minecraft.client.Minecraft;

/** Narrow AUI 1.2.8's document-level interception to actual tactical UI controls. */
public final class TacticalUiInput {
    private TacticalUiInput() {}

    /** Null keeps the dependency's normal behavior for every non-tactical document. */
    public static Boolean intercept(Document document, Position gui) {
        if (!UiZoom.owns(document) || gui == null) return null;
        // Pure HUDs are never an input surface, regardless of their root's layout box.
        if (BattleLog.PAGE.equals(document.getPath())) return BattleLog.interceptsAt(gui);
        if (document.getPath().startsWith("tactical/overlays/")) return false;
        var mc = Minecraft.getInstance();
        Position local = document.screenToDocumentPosition(gui);
        Element target = document.hitTest(local);
        Element context = document.getElementById("inv-context");
        if (context != null && target != null && context.contains(target)) return true;
        if (mc.screen instanceof ApricityContainerScreen screen
                && screen.getLinkedDocument() == document
                && screen.findSlotIndexAt(gui.x, gui.y) >= 0) {
            // SlotDataBinder handles geometry independently of DOM pointer-events.
            // Let MouseHandler continue into the vanilla container click/drag path.
            return false;
        }
        Element panel = document.getElementById("aui-scale-root");
        // HTML/BODY fill the viewport, but transparent battlefield space is not UI.
        return panel != null && target != null && panel.contains(target);
    }
}
