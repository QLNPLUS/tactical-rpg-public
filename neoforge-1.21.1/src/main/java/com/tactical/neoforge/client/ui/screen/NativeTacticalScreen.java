package com.tactical.neoforge.client.ui.screen;

import com.tactical.neoforge.client.ui.debug.DevProbe;
import com.tactical.neoforge.client.ui.hud.NativeHud;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Native base class for every tactical screen.
 *
 * <p>Three behaviours are not optional. {@code renderBackground} is empty because the 1.21.1 default
 * blurs and darkens the world, which would hide the battlefield the player is choosing a target in;
 * {@code isPauseScreen} is false because a tactical panel is part of the fight, not a pause; and the
 * interactive HUD gets the first look at every click, so the journal's toggle still works while a
 * page is open while the rest of the HUD only swallows the click.</p>
 *
 * <p>The wheel is <em>not</em> handled here on purpose: {@code ScreenEvent.MouseScrolled.Pre} already
 * ran the one chain (journal history, this page's list, the hotbar, the world) and canceled the event
 * when it was consumed. Re-running it here would scroll twice.</p>
 */
public abstract class NativeTacticalScreen extends Screen implements TacticalSurface {

    protected NativeTacticalScreen() {
        super(Component.empty());
    }

    /** Stable identity for the development probe. Never shown to the player. */
    public abstract String screenId();

    /** Recomputes the whole layout. Called on init, resize and preset change; never per frame. */
    protected abstract void rebuild();

    /** Draws the screen in GUI pixels. */
    protected abstract void drawContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick);

    /** Read-only JSON describing the visible controls, for the development probe. */
    protected abstract String probeJson();

    /**
     * True when the pointer is over one of this screen's surfaces, which means camera gestures belong
     * to the UI there instead of the world. Subclasses answer from their layout snapshot.
     */
    public boolean overUi(double guiX, double guiY) {
        return false;
    }

    /** True when one of this screen's controls consumed a click at that point. */
    public boolean consumesClick(double guiX, double guiY) {
        return false;
    }

    /**
     * Scrollable list of this page. Called once, from the wheel chain, only while the pointer is
     * inside the list; the page consumes the notch when it really scrolled.
     */
    public boolean scrollAt(double guiX, double guiY, double delta) {
        return false;
    }

    /**
     * True while a modal layer of this page owns the wheel - the colour picker, for instance.
     *
     * <p>The wheel chain asks this before it reaches the HUD's lists, the page's own list, the hotbar
     * or the camera, so an open modal never lets a notch change something behind it. Closing the modal
     * returns the wheel to its ordinary routing.</p>
     */
    public boolean consumesWheel() {
        return false;
    }

    @Override
    protected void init() {
        rebuild();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawContent(graphics, mouseX, mouseY, partialTick);
        if (DevProbe.enabled()) DevProbe.publish(screenId(), probeJson());
    }

    /** The battlefield must stay visible: no panorama, no blur, no dimming layer. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Input order: the interactive HUD, then the page's own controls, then whatever the page does
     * with the remaining battlefield clicks.
     */
    @Override
    public boolean mouseClicked(double pointerX, double pointerY, int button) {
        return NativeHud.click(pointerX, pointerY, button);
    }

    /** A HUD size drag keeps receiving the motion, whatever the page would otherwise do with it. */
    @Override
    public boolean mouseDragged(double pointerX, double pointerY, int button, double dragX, double dragY) {
        return NativeHud.dragged(pointerX, pointerY) || super.mouseDragged(pointerX, pointerY, button, dragX, dragY);
    }

    /**
     * A released size drag ends here and writes once. It must be offered before the page sees the
     * release, or the same gesture would also activate whatever is under the pointer.
     */
    @Override
    public boolean mouseReleased(double pointerX, double pointerY, int button) {
        if (NativeHud.release(pointerX, pointerY, button)) return true;
        return super.mouseReleased(pointerX, pointerY, button);
    }

    /** Already routed by the wheel chain; consuming here would apply the same notch twice. */
    @Override
    public boolean mouseScrolled(double pointerX, double pointerY, double scrollX, double scrollY) {
        return true;
    }
}
