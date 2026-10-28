package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.screen.ApricityScreen;

/** AUI screen with explicit size controls, non-scrolling panels and battle ESC handling. */
public class TacticalScreen extends ApricityScreen {

    private final Runnable onEscape;

    public TacticalScreen(String path, Runnable onEscape) {
        super(path);
        this.onEscape = onEscape;
    }

    @Override
    protected void init() {
        super.init();
        Document document = getLinkedDocument();
        UiZoom.applyAll();
        WheelGuard.install(document);
        WindowEntrance.install(document);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // The Pre listener is authoritative; this is a fallback for direct callbacks.
        if (BattleScreenInput.scroll(scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (BattleScreenInput.handleKey(this, keyCode, scanCode)) return true;
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && onEscape != null) {
            if (BattleMenu.closeSettings(getLinkedDocument())) return true;
            onEscape.run();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    protected Document document() {
        return getLinkedDocument();
    }
}
