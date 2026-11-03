package com.tactical.neoforge.client.ui.command;

import com.tactical.neoforge.client.UiPalette;
import com.tactical.neoforge.client.UiZoom;
import com.tactical.neoforge.client.ui.layout.Box;
import com.tactical.neoforge.client.ui.layout.HitTest;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.platform.Draw;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Palette;
import com.tactical.neoforge.client.ui.theme.Slate;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The colour settings popover: presets, a saturation/value panel, a hue bar, the current and preview
 * swatches, a HEX field and Reset / Cancel / Save, drawn natively and driven by real input.
 *
 * <p>Everything that decides <em>what</em> happens lives in the pure {@link ColorDraft} and
 * {@link ColorPopupLayout}; this class only draws their rectangles and routes the pointer and the
 * keyboard at them. Saving is the single moment the applied palette moves: it writes the one
 * preference, commits the file once, and the HUD and the world cells read the new colour on the next
 * frame because both read {@link UiPalette} live - there is no second copy to refresh.</p>
 */
public final class ColorSettingsPanel {

    /** Id in the screen's popover stack; the colour popover is always on top of the gear one. */
    public static final String POPUP_ID = "color-settings";

    private static final int HEX_DIGITS = 6;
    /** The vanilla caret rhythm, so the field feels like the one the player already knows. */
    private static final int CARET_BLINK_MS = 530;

    private ColorDraft draft;
    private ColorPopupLayout layout;
    private ColorPopupLayout.Content content;
    private long openedAt;
    private String pressedId;
    private String hoveredId;

    /** True while the pointer drags inside the saturation/value panel or along the hue bar. */
    private boolean draggingSv;
    private boolean draggingHue;
    private long caretSince;
    /**
     * What the three footer buttons really had drawn on them last frame, by control id. The probe
     * reports this so "the button shows Reset, not color-reset" is checkable without looking at a
     * picture - the wording and the control id are two different things.
     */
    private final java.util.Map<String, String> drawnLabels = new java.util.LinkedHashMap<>();

    public boolean active() {
        return draft != null;
    }

    public ColorDraft.Kind kind() {
        return draft == null ? null : draft.kind();
    }

    public ColorDraft draft() {
        return draft;
    }

    public boolean hexFocused() {
        return draft != null && draft.hexFocused();
    }

    public ColorPopupLayout layout() {
        return layout;
    }

    /** Starts editing one colour from what is applied right now; nothing is written. */
    public void open(ColorDraft.Kind kind) {
        Integer applied = kind == ColorDraft.Kind.RANGE ? UiZoom.rangeRgb() : UiZoom.themeRgb();
        draft = ColorDraft.of(kind, applied);
        layout = null;
        content = null;
        pressedId = null;
        hoveredId = null;
        draggingSv = false;
        draggingHue = false;
        caretSince = Util.getMillis();
        openedAt = Util.getMillis();
    }

    /** Cancel / Escape / outside click / losing the window: the draft is dropped, nothing is applied. */
    public void close() {
        draft = null;
        layout = null;
        content = null;
        pressedId = null;
        hoveredId = null;
        draggingSv = false;
        draggingHue = false;
    }

    // ---------------------------------------------------------------- layout

    /** Rebuilds the geometry; called by drawing and by input, so both see the same numbers. */
    public ColorPopupLayout ensure(int guiWidth, int guiHeight, double scale, boolean narrow,
                                  Rect gearPopover, Font font) {
        if (draft == null) return null;
        content = new ColorPopupLayout.Content(ColorPopupLayout.presetsOf(draft.kind()),
                font::width,
                ColorTextResolver.get(ColorText.RESET),
                ColorTextResolver.get(ColorText.CANCEL),
                ColorTextResolver.get(ColorText.SAVE));
        layout = ColorPopupLayout.compute(guiWidth, guiHeight, scale, narrow, gearPopover,
                draft.canSave(), content);
        return layout;
    }

    // ---------------------------------------------------------------- drawing

    public void draw(GuiGraphics graphics, Font font, double scale, long now) {
        if (draft == null || layout == null) return;
        Rect panel = layout.panel();
        if (panel.w() <= 0) return;
        double enter = Motion.easeOutCubic(Motion.progress(now - openedAt, Motion.PANEL_ENTER_MS));
        Draw.shadow(graphics, panel, Scale.SMALL_SHADOW, col(Slate.SHADOW_SM, enter), false);
        // A floating surface is opaque from the first frame; only its contents fade in.
        Draw.chip(graphics, panel, Slate.FLOAT);
        drawTitle(graphics, font, scale, enter);
        Draw.accentRule(graphics, layout.rect(ColorPopupLayout.RULE));
        drawPresets(graphics, font, scale, enter);
        if (layout.showsSv()) drawSaturationValue(graphics, scale);
        if (layout.showsHue()) drawHueBar(graphics, scale);
        drawPair(graphics, font, scale, enter);
        drawHex(graphics, font, scale, now, enter);
        drawHint(graphics, font, scale, enter);
        Draw.separator(graphics, layout.rect(ColorPopupLayout.FOOTER_RULE));
        drawButton(graphics, font, scale, ColorPopupLayout.RESET, true, enter);
        drawButton(graphics, font, scale, ColorPopupLayout.CANCEL, true, enter);
        drawButton(graphics, font, scale, ColorPopupLayout.SAVE, draft.canSave(), enter);
    }

    private void drawTitle(GuiGraphics graphics, Font font, double scale, double alpha) {
        Rect title = layout.rect(ColorPopupLayout.TITLE);
        String text = ColorTextResolver.get(ColorText.titleKey(draft.kind()));
        Draw.scaledText(graphics, font, fit(font, text, title.w(), scale), title.x(),
                baseline(title, scale), scale, col(Slate.TEXT, alpha));
    }

    private void drawPresets(GuiGraphics graphics, Font font, double scale, double alpha) {
        List<Integer> presets = layout.presets();
        for (int index = 0; index < presets.size(); index++) {
            Rect box = layout.rect(ColorPopupLayout.PRESET + index);
            if (box.w() <= 0) continue;
            int colour = 0xFF000000 | (presets.get(index) & 0xFFFFFF);
            boolean selected = (presets.get(index) & 0xFFFFFF) == draft.draft();
            boolean hovered = (ColorPopupLayout.PRESET + index).equals(hoveredId);
            if (selected) {
                // Selected: a 1px accent-light ring outside the swatch and a 1px slab line inside it,
                // so the swatch reads as selected on any colour it happens to be.
                Draw.fill(graphics, box.x() - 1, box.y() - 1, box.w() + 2, box.h() + 2,
                        col(UiPalette.accentLight(), alpha));
                Draw.fill(graphics, box, col(Slate.SLAB, alpha));
                Draw.chip(graphics, box.inset(1), col(colour, alpha));
            } else if (hovered) {
                Draw.fill(graphics, box.x() - 1, box.y() - 1, box.w() + 2, box.h() + 2,
                        col(Slate.TEXT3, alpha));
                Draw.chip(graphics, box, col(colour, alpha));
            } else {
                Draw.chip(graphics, box, col(colour, alpha));
            }
        }
    }

    /** 69 columns: each one a vertical gradient from that saturation's brightest to its darkest. */
    private void drawSaturationValue(GuiGraphics graphics, double scale) {
        Rect area = layout.rect(ColorPopupLayout.SV);
        if (area.w() <= 0) return;
        double hue = draft.hue();
        int columns = Math.max(1, area.w() / 2);
        for (int column = 0; column < columns; column++) {
            double saturation = columns <= 1 ? 0.0D : column / (double) (columns - 1);
            int x = area.x() + column * 2;
            int width = Math.min(2, area.right() - x);
            int top = Palette.accent(Palette.fromHsv(hue, saturation, 1.0D));
            int bottom = Palette.accent(Palette.fromHsv(hue, saturation, 0.0D));
            graphics.fillGradient(x, area.y(), x + width, area.bottom(), top, bottom);
        }
        outline(graphics, area, Slate.LINE);
        int size = Scale.effectivePx(5, scale);
        int markerX = area.x() + (int) Math.round(draft.saturation() * (area.w() - size));
        int markerY = area.y() + (int) Math.round((1.0D - draft.value()) * (area.h() - size));
        marker(graphics, Rect.of(markerX, markerY, size, size),
                Palette.accent(Palette.fromHsv(draft.hue(), draft.saturation(), draft.value())));
    }

    private void drawHueBar(GuiGraphics graphics, double scale) {
        Rect area = layout.rect(ColorPopupLayout.HUE);
        if (area.w() <= 0) return;
        int columns = Math.max(1, area.w() / 2);
        for (int column = 0; column < columns; column++) {
            double hue = 360.0D * column / columns;
            int x = area.x() + column * 2;
            int width = Math.min(2, area.right() - x);
            Draw.fill(graphics, x, area.y(), width, area.h(), Palette.accent(Palette.fromHsv(hue, 1.0D, 1.0D)));
        }
        outline(graphics, area, Slate.LINE);
        // The marker stands two pixels proud of the bar so it is grabbable at any preset.
        int width = Scale.effectivePx(3, scale);
        int tall = area.h() + Scale.effectivePx(4, scale);
        int markerX = area.x() + (int) Math.round(draft.hue() / 360.0D * (area.w() - width));
        marker(graphics, Rect.of(markerX, area.y() - Scale.effectivePx(2, scale), width, tall),
                Palette.accent(Palette.fromHsv(draft.hue(), 1.0D, 1.0D)));
    }

    /** The current / preview pair: two labels with a framed swatch of what is applied and what is not. */
    private void drawPair(GuiGraphics graphics, Font font, double scale, double alpha) {
        swatchRow(graphics, font, scale, layout.rect(ColorPopupLayout.CURRENT),
                layout.rect(ColorPopupLayout.CURRENT_SWATCH), ColorTextResolver.get(ColorText.CURRENT),
                draft.saved(), alpha);
        swatchRow(graphics, font, scale, layout.rect(ColorPopupLayout.PREVIEW),
                layout.rect(ColorPopupLayout.PREVIEW_SWATCH), ColorTextResolver.get(ColorText.PREVIEW),
                draft.draft(), alpha);
    }

    private void swatchRow(GuiGraphics graphics, Font font, double scale, Rect row, Rect swatch, String label,
                           int colour, double alpha) {
        if (row.w() <= 0) return;
        int lineY = baseline(row, scale);
        Draw.scaledText(graphics, font, fit(font, label, row.w(), scale), row.x(), lineY, scale,
                col(Slate.TEXT2, alpha));
        if (swatch.w() <= 0) return;
        outline(graphics, swatch, Slate.LINE);
        Draw.fill(graphics, swatch.inset(1), col(Palette.accent(colour), alpha));
    }

    private void drawHex(GuiGraphics graphics, Font font, double scale, long now, double alpha) {
        Rect label = layout.rect(ColorPopupLayout.HEX);
        Rect field = layout.rect(ColorPopupLayout.HEX_FIELD);
        if (field.w() <= 0) return;
        if (label.w() > 0) {
            Draw.scaledText(graphics, font, fit(font, ColorTextResolver.get(ColorText.HEX), label.w(), scale),
                    label.x(), baseline(label, scale), scale, col(Slate.TEXT2, alpha));
        }
        Draw.chip(graphics, field, col(Slate.SUNK, alpha));
        int pad = Scale.effectivePx(4, scale);
        int textLeft = field.x() + pad;
        int innerBudget = field.w() - pad * 2;
        int lineY = baseline(field, scale);
        int hashWidth = TextMetrics.width(font.width("#"), scale);
        Draw.scaledText(graphics, font, "#", textLeft, lineY, scale, col(Slate.TEXT3, alpha));
        int digitsLeft = textLeft + hashWidth;
        int digitsBudget = Math.max(1, innerBudget - hashWidth);
        // Selection first, then the glyphs, then the caret - the order the vanilla field paints in.
        if (draft.hexFocused() && draft.hasSelection()) {
            int from = TextMetrics.width(font.width(draft.hex().substring(0, draft.selectionStart())), scale);
            int to = TextMetrics.width(font.width(draft.hex().substring(0, draft.selectionEnd())), scale);
            Draw.fill(graphics, digitsLeft + from, field.y() + Scale.effectivePx(2, scale),
                    Math.max(1, to - from), field.h() - Scale.effectivePx(4, scale),
                    col(UiPalette.accentDark(), alpha));
        }
        Draw.scaledText(graphics, font, draft.hex(), digitsLeft, lineY, scale, col(Slate.TEXT, alpha));
        if (draft.hexFocused()) {
            boolean visible = ((now - caretSince) / CARET_BLINK_MS) % 2 == 0;
            if (visible) {
                int offset = TextMetrics.width(font.width(draft.hex().substring(0, draft.caret())), scale);
                Draw.fill(graphics, Math.min(digitsLeft + offset, digitsLeft + digitsBudget - 1),
                        field.y() + Scale.effectivePx(2, scale), 1, Scale.effectivePx(Motion.LINE, scale),
                        col(Slate.TEXT, alpha));
            }
        }
        // The ring says what the field means: an error borrows HP, focus borrows the accent.
        if (draft.hexError()) {
            outline(graphics, field, Slate.HP);
        } else if (draft.hexFocused()) {
            outline(graphics, field, UiPalette.accent());
        }
    }

    private void drawHint(GuiGraphics graphics, Font font, double scale, double alpha) {
        Rect hint = layout.rect(ColorPopupLayout.HINT);
        if (hint.w() <= 0) return;
        boolean error = draft.hexError();
        String text = ColorTextResolver.get(error ? ColorText.HINT_ERROR : ColorText.HINT);
        Draw.scaledText(graphics, font, fit(font, text, hint.w(), scale), hint.x(), hint.y(), scale,
                col(error ? Slate.HP : Slate.TEXT3, alpha));
    }

    /**
     * One of the three footer buttons. The label always comes from {@link ColorText#buttonKey}, never
     * from the control id: a button that draws its own id is exactly the bug this signature prevents.
     */
    private void drawButton(GuiGraphics graphics, Font font, double scale, String id,
                            boolean enabled, double alpha) {
        Rect rect = layout.rect(id);
        if (rect.w() <= 0) return;
        boolean hovered = enabled && id.equals(hoveredId);
        boolean pressed = enabled && id.equals(pressedId) && hovered;
        int base = !enabled || pressed ? Slate.SUNK : Draw.blend(Slate.RAISE, Slate.HOVER, hovered ? 1.0D : 0.0D);
        Draw.chip(graphics, rect, col(base, alpha));
        int pad = Scale.effectivePx(Motion.BUTTON_PAD_X, scale);
        String label = TextMetrics.iconOrEllipsis(ColorTextResolver.get(ColorText.buttonKey(id)),
                Math.max(1, rect.w() - pad * 2), scale, font::width);
        drawnLabels.put(id, label);
        int textWidth = TextMetrics.width(font.width(label), scale);
        Draw.scaledText(graphics, font, label, rect.x() + (rect.w() - textWidth) / 2,
                baseline(rect, scale) + (pressed ? 1 : 0), scale,
                col(enabled ? Slate.TEXT : Slate.TEXT3, alpha));
    }

    /** A 5×5 (or 3×12) marker: a TEXT ring, a SLAB line inside and the live colour in the middle. */
    private void marker(GuiGraphics graphics, Rect box, int colour) {
        Draw.fill(graphics, box, Slate.TEXT);
        if (box.w() - 2 > 0 && box.h() - 2 > 0) Draw.fill(graphics, box.inset(1), Slate.SLAB);
        if (box.w() - 4 > 0 && box.h() - 4 > 0) Draw.fill(graphics, box.inset(2), colour);
    }

    private static void outline(GuiGraphics graphics, Rect rect, int color) {
        if (rect.w() <= 0 || rect.h() <= 0) return;
        Draw.fill(graphics, rect.x(), rect.y(), rect.w(), 1, color);
        Draw.fill(graphics, rect.x(), rect.bottom() - 1, rect.w(), 1, color);
        Draw.fill(graphics, rect.x(), rect.y(), 1, rect.h(), color);
        Draw.fill(graphics, rect.right() - 1, rect.y(), 1, rect.h(), color);
    }

    // ---------------------------------------------------------------- input

    /** Hover state of the last frame; the popover never trusts a stale id for a press. */
    public void hover(double pointerX, double pointerY) {
        if (layout == null) {
            hoveredId = null;
            return;
        }
        Box hit = HitTest.topmost(boxes(), pointerX, pointerY);
        hoveredId = hit == null ? null : hit.id();
    }

    private List<Box> boxes() {
        List<Box> boxes = new ArrayList<>();
        if (layout == null) return boxes;
        for (String id : List.of(ColorPopupLayout.SAVE, ColorPopupLayout.CANCEL, ColorPopupLayout.RESET,
                ColorPopupLayout.HEX_FIELD)) {
            boxes.add(Box.of(id, layout.rect(id)));
        }
        if (layout.showsSv()) boxes.add(Box.of(ColorPopupLayout.SV, layout.rect(ColorPopupLayout.SV)));
        if (layout.showsHue()) boxes.add(Box.of(ColorPopupLayout.HUE, layout.rect(ColorPopupLayout.HUE)));
        for (int index = 0; index < layout.presets().size(); index++) {
            boxes.add(Box.of(ColorPopupLayout.PRESET + index, layout.rect(ColorPopupLayout.PRESET + index)));
        }
        return boxes;
    }

    /** True when the pointer is inside this popover, which owns the layer above everything else. */
    public boolean contains(double pointerX, double pointerY) {
        return layout != null && layout.panel().contains(pointerX, pointerY);
    }

    /**
     * A press inside the popover. Nothing is applied here: Save is the only control that writes, and
     * the buttons activate on release like every other button of this UI.
     *
     * @return true when the popover took the click
     */
    public boolean mousePressed(double pointerX, double pointerY, int button, Font font, double scale) {
        if (draft == null || layout == null) return false;
        if (!contains(pointerX, pointerY)) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        Box hit = HitTest.topmost(boxes(), pointerX, pointerY);
        String id = hit == null ? null : hit.id();
        pressedId = id;
        // Focus follows the click: the HEX field is the only control that keeps it.
        if (!ColorPopupLayout.HEX_FIELD.equals(id)) draft.focusHex(false);
        if (id == null) return true;
        switch (id) {
            case ColorPopupLayout.SV -> {
                draggingSv = true;
                applySaturationValue(pointerX, pointerY);
            }
            case ColorPopupLayout.HUE -> {
                draggingHue = true;
                applyHue(pointerX);
            }
            case ColorPopupLayout.HEX_FIELD -> {
                draft.focusHex(true);
                caretSince = Util.getMillis();
                placeCaret(pointerX, font, scale);
            }
            default -> {
                // Presets and the three buttons act on release.
            }
        }
        return true;
    }

    public boolean mouseDragged(double pointerX, double pointerY) {
        if (draft == null) return false;
        if (draggingSv) {
            applySaturationValue(pointerX, pointerY);
            return true;
        }
        if (draggingHue) {
            applyHue(pointerX);
            return true;
        }
        return false;
    }

    /**
     * A release ends any slider drag and activates the button or preset the press started on, exactly
     * like the rest of the UI: only a release over the same control counts.
     *
     * @return true when the popover consumed the release
     */
    public boolean mouseReleased(double pointerX, double pointerY, int button) {
        if (draft == null) return false;
        boolean wasDragging = draggingSv || draggingHue;
        draggingSv = false;
        draggingHue = false;
        String pressed = pressedId;
        pressedId = null;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (pressed == null || wasDragging) return wasDragging || contains(pointerX, pointerY);
        Box hit = HitTest.topmost(boxes(), pointerX, pointerY);
        if (hit != null && pressed.equals(hit.id())) activate(pressed);
        return true;
    }

    private void activate(String id) {
        if (id.startsWith(ColorPopupLayout.PRESET)) {
            int index = Integer.parseInt(id.substring(ColorPopupLayout.PRESET.length()));
            List<Integer> presets = layout.presets();
            if (index >= 0 && index < presets.size()) draft.preset(presets.get(index));
            return;
        }
        switch (id) {
            case ColorPopupLayout.RESET -> {
                draft.resetToDefault();
                caretSince = Util.getMillis();
            }
            case ColorPopupLayout.CANCEL -> close();
            case ColorPopupLayout.SAVE -> save();
            default -> {
            }
        }
    }

    /** The one write: the draft becomes the applied colour and the file is committed once. */
    public boolean save() {
        if (draft == null) return false;
        Integer value = draft.savedValue();
        if (value == null) return false;
        if (draft.kind() == ColorDraft.Kind.RANGE) {
            UiZoom.setColors(UiZoom.themeRgb(), value);
        } else {
            UiZoom.setColors(value, UiZoom.rangeRgb());
        }
        UiZoom.commit();
        close();
        return true;
    }

    private void applySaturationValue(double pointerX, double pointerY) {
        Rect area = layout.rect(ColorPopupLayout.SV);
        if (area.w() <= 0 || area.h() <= 0) return;
        double saturation = (pointerX - area.x()) / Math.max(1.0D, area.w() - 1.0D);
        double value = 1.0D - (pointerY - area.y()) / Math.max(1.0D, area.h() - 1.0D);
        draft.saturationValue(saturation, value);
    }

    private void applyHue(double pointerX) {
        Rect area = layout.rect(ColorPopupLayout.HUE);
        if (area.w() <= 0) return;
        double hue = 360.0D * (pointerX - area.x()) / Math.max(1.0D, area.w() - 1.0D);
        draft.hue(hue);
    }

    /** Clicking inside the field puts the caret where the click landed, like the vanilla field. */
    private void placeCaret(double pointerX, Font font, double scale) {
        Rect field = layout.rect(ColorPopupLayout.HEX_FIELD);
        int pad = Scale.effectivePx(4, scale);
        int left = field.x() + pad + TextMetrics.width(font.width("#"), scale);
        int offset = Math.max(0, (int) Math.round(pointerX) - left);
        int index = 0;
        while (index < draft.hex().length()) {
            int width = TextMetrics.width(font.width(draft.hex().substring(0, index + 1)), scale);
            if (width > offset) break;
            index++;
        }
        draft.caretTo(index);
    }

    /**
     * Keys while the popover is open. A focused HEX field swallows everything else, so the hotbar and
     * the battle shortcuts can never fire from inside the field; Escape is always the popover's.
     *
     * @return true when the popover consumed the key
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (draft == null) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (!draft.hexFocused()) return false;
        boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (control) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_A -> draft.selectAll();
                case GLFW.GLFW_KEY_C -> setClipboard(draft.selectedText());
                case GLFW.GLFW_KEY_V -> draft.insertText(getClipboard());
                case GLFW.GLFW_KEY_X -> {
                    setClipboard(draft.selectedText());
                    draft.deleteHex();
                }
                default -> {
                    return true;
                }
            }
            caretSince = Util.getMillis();
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> draft.backspaceHex();
            case GLFW.GLFW_KEY_DELETE -> draft.deleteHex();
            case GLFW.GLFW_KEY_LEFT -> draft.moveCaret(-1, (modifiers & GLFW.GLFW_MOD_SHIFT) != 0);
            case GLFW.GLFW_KEY_RIGHT -> draft.moveCaret(1, (modifiers & GLFW.GLFW_MOD_SHIFT) != 0);
            case GLFW.GLFW_KEY_HOME -> draft.caretTo(0);
            case GLFW.GLFW_KEY_END -> draft.caretTo(HEX_DIGITS);
            default -> {
                return true;
            }
        }
        caretSince = Util.getMillis();
        return true;
    }

    /** Typed characters; only the focused HEX field takes any, and only its hex digits. */
    public boolean charTyped(char codePoint) {
        if (draft == null || !draft.hexFocused()) return false;
        draft.typeHex(codePoint);
        caretSince = Util.getMillis();
        return true;
    }

    private static String getClipboard() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft == null || minecraft.keyboardHandler == null ? ""
                    : minecraft.keyboardHandler.getClipboard();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void setClipboard(String value) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.keyboardHandler != null) {
                minecraft.keyboardHandler.setClipboard(value == null ? "" : value);
            }
        } catch (Throwable ignored) {
            // A missing clipboard is not a reason to break the picker.
        }
    }

    // ---------------------------------------------------------------- probe

    /** Development only: exactly what the popover shows and what Save would write. */
    public String probeJson() {
        if (draft == null || layout == null) return "null";
        StringBuilder presets = new StringBuilder("[");
        for (int index = 0; index < layout.presets().size(); index++) {
            if (index > 0) presets.append(',');
            Rect box = layout.rect(ColorPopupLayout.PRESET + index);
            presets.append(String.format(Locale.ROOT, "{\"hex\":\"%s\",\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d}",
                    Palette.hex(layout.presets().get(index)), box.x(), box.y(), box.w(), box.h()));
        }
        presets.append(']');
        Rect panel = layout.panel();
        Rect save = layout.rect(ColorPopupLayout.SAVE);
        return String.format(Locale.ROOT,
                "{\"kind\":\"%s\",\"level\":\"%s\",\"panel\":{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d},"
                        + "\"title\":\"%s\",\"draft\":\"%s\",\"applied\":\"%s\",\"hexError\":%b,"
                        + "\"hexFocused\":%b,\"canSave\":%b,\"buttonsStacked\":%b,\"saveEnabled\":%b,"
                        + "\"save\":{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d},"
                        + "\"controls\":%s,"
                        + "\"labels\":{\"title\":\"%s\",\"current\":\"%s\",\"preview\":\"%s\","
                        + "\"reset\":\"%s\",\"cancel\":\"%s\",\"save\":\"%s\"},"
                        + "\"drawn\":%s,"
                        + "\"presets\":%s}",
                draft.kind(), layout.level(), panel.x(), panel.y(), panel.w(), panel.h(),
                escape(ColorTextResolver.get(ColorText.titleKey(draft.kind()))),
                Palette.hex(draft.draft()), Palette.hex(draft.saved()), draft.hexError(),
                draft.hexFocused(), draft.canSave(), layout.buttonsStacked(), layout.saveEnabled(),
                save.x(), save.y(), save.w(), save.h(), controlsJson(),
                escape(ColorTextResolver.get(ColorText.titleKey(draft.kind()))),
                escape(ColorTextResolver.get(ColorText.CURRENT)), escape(ColorTextResolver.get(ColorText.PREVIEW)),
                escape(ColorTextResolver.get(ColorText.RESET)), escape(ColorTextResolver.get(ColorText.CANCEL)),
                escape(ColorTextResolver.get(ColorText.SAVE)), drawnJson(), presets);
    }

    /** What each footer button really drew, so a regression to id-as-label is machine-checkable. */
    private String drawnJson() {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, String> entry : drawnLabels.entrySet()) {
            if (!first) builder.append(',');
            first = false;
            builder.append('"').append(entry.getKey()).append("\":\"").append(escape(entry.getValue())).append('"');
        }
        return builder.append('}').toString();
    }

    /** Every control a driver has to hit, as a rectangle, so no coordinate is hand-written. */
    private String controlsJson() {
        StringBuilder builder = new StringBuilder("{");
        String[] ids = {ColorPopupLayout.SV, ColorPopupLayout.HUE, ColorPopupLayout.HEX_FIELD,
                ColorPopupLayout.RESET, ColorPopupLayout.CANCEL, ColorPopupLayout.SAVE,
                ColorPopupLayout.CURRENT, ColorPopupLayout.PREVIEW, ColorPopupLayout.TITLE,
                ColorPopupLayout.RULE, ColorPopupLayout.HINT};
        boolean first = true;
        for (String id : ids) {
            Rect rect = layout.rect(id);
            if (rect.w() <= 0 || rect.h() <= 0) continue;
            if (!first) builder.append(',');
            first = false;
            builder.append('"').append(id).append("\":{\"x\":").append(rect.x()).append(",\"y\":")
                    .append(rect.y()).append(",\"w\":").append(rect.w()).append(",\"h\":").append(rect.h())
                    .append('}');
        }
        return builder.append('}').toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------------------------------------------------------- helpers

    private String fit(Font font, String text, int width, double scale) {
        return TextMetrics.ellipsis(text, width, scale, font::width);
    }

    private static int baseline(Rect box, double scale) {
        int line = (int) Math.round(Motion.LINE * scale);
        return box.y() + Math.max(0, (box.h() - line) / 2);
    }

    private static int col(int color, double alpha) {
        return Motion.withAlpha(color, alpha);
    }
}
