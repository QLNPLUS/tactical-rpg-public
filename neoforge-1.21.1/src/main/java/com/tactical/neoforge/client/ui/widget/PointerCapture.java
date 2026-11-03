package com.tactical.neoforge.client.ui.widget;

import java.util.Objects;

/** One-shot capture: only release over the originally pressed control activates it. */
public final class PointerCapture {
    private String pressed;
    public void press(String id) { pressed = id; }
    public void cancel() { pressed = null; }
    public String pressed() { return pressed; }
    public String release(String over) {
        String result = pressed != null && Objects.equals(pressed, over) ? pressed : null;
        cancel();
        return result;
    }
}
