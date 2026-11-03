package com.tactical.neoforge.client.ui.command;

import com.tactical.neoforge.client.ui.theme.Palette;

/**
 * The colour settings' edit session: a draft that is previewed live and applied only on Save.
 *
 * <p>Pure on purpose. The rules the user cares about are all here and all testable: nothing touches
 * the applied palette until Save, Cancel and Escape throw the draft away, Reset only re-points the
 * draft, an illegal HEX value can never be saved, and only RGB is editable - alpha is never offered.</p>
 *
 * <p>The hue, saturation and value are remembered next to the colour, not re-derived from it on every
 * edit. A colour has no hue once it is grey, and no saturation once it is black, so reading them back
 * from the RGB would send the picker to red as soon as the user dragged the value down: the approved
 * behaviour is that dragging back up returns the hue the user chose, never a different one.</p>
 */
public final class ColorDraft {

    /** Which of the two colours is being edited. */
    public enum Kind { THEME, RANGE }

    private static final int HEX_LENGTH = 6;

    private final Kind kind;
    private final int saved;
    private int draft;
    private String hex;
    private boolean hexFocused;
    private boolean hexError;
    /** Remembered picker state: hue in degrees, saturation and value in 0..1. */
    private double hue;
    private double saturation;
    private double value;
    /** Caret and selection anchor of the HEX field, both 0..length; equal means "no selection". */
    private int caret;
    private int anchor;

    private ColorDraft(Kind kind, int saved) {
        this.kind = kind;
        this.saved = saved & 0xFFFFFF;
        this.draft = this.saved;
        this.hex = Palette.hex(this.saved);
        adopt(this.saved);
        this.caret = HEX_LENGTH;
        this.anchor = HEX_LENGTH;
    }

    /** A draft for one colour, starting from what is currently applied. */
    public static ColorDraft of(Kind kind, Integer appliedRgb) {
        int fallback = kind == Kind.RANGE ? Palette.DEFAULT_RANGE : Palette.DEFAULT_THEME;
        return new ColorDraft(kind, Palette.rgb(appliedRgb, fallback));
    }

    public Kind kind() {
        return kind;
    }

    public int saved() {
        return saved;
    }

    public int draft() {
        return draft;
    }

    public String hex() {
        return hex;
    }

    public boolean hexFocused() {
        return hexFocused;
    }

    public boolean hexError() {
        return hexError;
    }

    /** The remembered hue in degrees: what the hue bar's marker is drawn at, even for a grey draft. */
    public double hue() {
        return hue;
    }

    public double saturation() {
        return saturation;
    }

    public double value() {
        return value;
    }

    public int caret() {
        return caret;
    }

    public int selectionStart() {
        return Math.min(caret, anchor);
    }

    public int selectionEnd() {
        return Math.max(caret, anchor);
    }

    public boolean hasSelection() {
        return selectionStart() != selectionEnd();
    }

    /** The eight presets of this colour: the theme's or the range colour's own set. */
    public java.util.List<Integer> presets() {
        return kind == Kind.RANGE ? Palette.RANGE_PRESETS : Palette.THEME_PRESETS;
    }

    public void preset(int rgb) {
        adopt(rgb & 0xFFFFFF);
        draft = rgb & 0xFFFFFF;
        hex = Palette.hex(draft);
        hexError = false;
        caret = Math.min(caret, hex.length());
        anchor = caret;
    }

    /**
     * A point on the saturation/value panel: saturation across, value down.
     *
     * <p>The hue comes from the remembered value, so dragging the value to zero - black, which has no
     * hue of its own - and back up returns the colour the user was working with instead of red.</p>
     */
    public void saturationValue(double newSaturation, double newValue) {
        saturation = clamp01(newSaturation);
        value = clamp01(newValue);
        draft = Palette.fromHsv(hue, saturation, value);
        hex = Palette.hex(draft);
        hexError = false;
        caret = Math.min(caret, hex.length());
        anchor = caret;
    }

    public void hue(double degrees) {
        hue = ((degrees % 360.0D) + 360.0D) % 360.0D;
        // A hue is invisible at zero saturation or zero value: the marker still moves, so the colour
        // is lifted to something the user can actually see instead of a preview that never changes.
        if (saturation <= 0.0D) saturation = 1.0D;
        if (value <= 0.0D) value = 1.0D;
        draft = Palette.fromHsv(hue, saturation, value);
        hex = Palette.hex(draft);
        hexError = false;
        caret = Math.min(caret, hex.length());
        anchor = caret;
    }

    public void focusHex(boolean focused) {
        hexFocused = focused;
        if (focused) {
            caret = hex.length();
            anchor = caret;
        }
    }

    // ---------------------------------------------------------------- HEX field editing

    /** Types one character into the HEX field; anything but 0-9 / A-F / a-f is refused outright. */
    public void typeHex(int codePoint) {
        if (!hexFocused) return;
        int ascii = Character.toUpperCase(codePoint);
        if (Character.digit(ascii, 16) < 0) return;
        insert(Character.toString((char) ascii));
    }

    public void backspaceHex() {
        if (!hexFocused) return;
        if (hasSelection()) {
            replaceSelection("");
            return;
        }
        if (caret <= 0) return;
        hex = hex.substring(0, caret - 1) + hex.substring(caret);
        caret--;
        anchor = caret;
        applyHexIfLegal();
    }

    public void deleteHex() {
        if (!hexFocused) return;
        if (hasSelection()) {
            replaceSelection("");
            return;
        }
        if (caret >= hex.length()) return;
        hex = hex.substring(0, caret) + hex.substring(caret + 1);
        applyHexIfLegal();
    }

    /** Moves the caret; {@code extend} keeps the anchor, which is how a selection is drawn. */
    public void moveCaret(int delta, boolean extend) {
        int next = Math.max(0, Math.min(hex.length(), caret + delta));
        caret = next;
        if (!extend) anchor = next;
    }

    public void caretTo(int index) {
        caret = Math.max(0, Math.min(hex.length(), index));
        anchor = caret;
    }

    public void selectRange(int from, int to) {
        int low = Math.max(0, Math.min(hex.length(), Math.min(from, to)));
        int high = Math.max(0, Math.min(hex.length(), Math.max(from, to)));
        anchor = low;
        caret = high;
    }

    public void selectAll() {
        anchor = 0;
        caret = hex.length();
    }

    /** The selected text, or the whole field when the selection is empty, as a copy would give. */
    public String selectedText() {
        return hasSelection() ? hex.substring(selectionStart(), selectionEnd()) : hex;
    }

    /**
     * Replaces the whole field, as a paste into an empty selection-less field would.
     *
     * <p>Nothing is cut down to six digits any more: a value that is too long or contains anything
     * else stays an error that keeps the last legal preview, instead of being silently shortened into
     * something that could be saved as if the user had typed it.</p>
     */
    public void setHex(String text) {
        hex = normaliseInput(text);
        caret = hex.length();
        anchor = caret;
        applyHexIfLegal();
    }

    /** Inserts a pasted string at the caret, replacing the selection; the same legality rule applies. */
    public void insertText(String text) {
        if (!hexFocused) return;
        insert(normaliseInput(text));
    }

    private static String normaliseInput(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("#")) text = text.substring(1);
        return text;
    }

    private void insert(String addition) {
        if (hasSelection()) {
            replaceSelection(addition);
            return;
        }
        if (hex.length() + addition.length() > HEX_LENGTH) return;
        hex = hex.substring(0, caret) + addition + hex.substring(caret);
        caret += addition.length();
        anchor = caret;
        applyHexIfLegal();
    }

    private void replaceSelection(String addition) {
        int from = selectionStart();
        int to = selectionEnd();
        String next = hex.substring(0, from) + addition + hex.substring(to);
        hex = next;
        caret = from + addition.length();
        anchor = caret;
        applyHexIfLegal();
    }

    /**
     * The preview follows the last legal colour: while the field is incomplete or illegal the error
     * ring is shown and the preview keeps the previous legal colour, never a made-up one.
     */
    private void applyHexIfLegal() {
        if (Palette.validHex(hex)) {
            draft = Palette.parseHex(hex);
            hexError = false;
            adopt(draft);
        } else {
            hexError = true;
        }
    }

    /** Back to this colour's default; the draft changes, the applied colour does not. */
    public void resetToDefault() {
        preset(kind == Kind.RANGE ? Palette.DEFAULT_RANGE : Palette.DEFAULT_THEME);
        hexFocused = false;
    }

    /** Save is enabled only for a legal six-digit draft that actually differs from what is applied. */
    public boolean canSave() {
        return !hexError && Palette.validHex(hex) && (draft & 0xFFFFFF) != saved;
    }

    /** The colour Save would apply, or null when Save is unavailable. */
    public Integer savedValue() {
        return canSave() ? draft & 0xFFFFFF : null;
    }

    /**
     * Takes a colour as the new reference point. A grey keeps the hue it had and a black keeps both
     * its hue and its saturation, because neither is recoverable from the RGB itself.
     */
    private void adopt(int rgb) {
        double[] hsv = Palette.toHsv(rgb & 0xFFFFFF);
        value = hsv[2];
        if (hsv[2] <= 0.0D) return;
        saturation = hsv[1];
        if (hsv[1] > 0.0D) hue = hsv[0];
    }

    private static double clamp01(double input) {
        return Math.max(0.0D, Math.min(1.0D, input));
    }
}
