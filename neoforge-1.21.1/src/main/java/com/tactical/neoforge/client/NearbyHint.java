package com.tactical.neoforge.client;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import net.minecraft.client.Minecraft;

/**
 * Non-focus-stealing hint that a battle is running nearby.
 *
 * <p>It is a passive overlay document, so it never takes the cursor, never opens a screen and never
 * interrupts an open inventory, chat or death screen. It disappears as soon as this client is in a
 * battle, the list is empty, or the player cannot act yet.</p>
 */
public final class NearbyHint {
    public static final String PAGE = "tactical/overlays/nearby_hint.html";

    private static Document document;
    private static boolean shown;

    private NearbyHint() { }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean wanted = minecraft.level != null && minecraft.player != null
                && minecraft.getConnection() != null
                && BattleDiscoveryClientState.hintVisible()
                && !(minecraft.screen instanceof TacticalScreen);
        if (!wanted) {
            close();
            return;
        }
        if (document == null || document.isDisposed()) {
            document = ApricityUI.createDocument(PAGE);
            if (document == null) return;
            shown = false;
            UiZoom.applyAll();
        }
        if (shown) return;
        var root = document.getElementById("hint-root");
        if (root != null) root.setAttribute("data-state", "visible");
        shown = true;
    }

    public static void close() {
        if (document != null && shown) ApricityUI.removeDocument(PAGE);
        document = null;
        shown = false;
    }
}
