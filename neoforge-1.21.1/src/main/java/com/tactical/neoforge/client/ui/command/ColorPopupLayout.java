package com.tactical.neoforge.client.ui.command;

import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Palette;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Geometry of the colour settings popover, in the approved order and with its approved degradations.
 *
 * <p>Pure: one walk down the popover produces every rectangle, so drawing, hit testing and the probe
 * read the same numbers. The three heights are real sums of the rows this layout actually emits - the
 * presets' second row on a narrow panel, the stacked current/preview pair and the vertically stacked
 * buttons are counted - so the popover can never be laid out at the old 192/132/118 total while
 * carrying more content than that.</p>
 *
 * <p>The button row is decided by measurement, not by a guess: three 32-pixel columns on a narrow
 * panel cannot hold the English "Cancel", and the approved degradation is then to stack the three
 * buttons at full inner width instead of truncating one of them.</p>
 */
public final class ColorPopupLayout {

    public static final String PANEL = "color-panel";
    public static final String TITLE = "color-title";
    public static final String RULE = "color-rule";
    public static final String PRESET = "color-preset-";
    public static final String SV = "color-sv";
    public static final String HUE = "color-hue";
    public static final String CURRENT = "color-current";
    public static final String CURRENT_SWATCH = "color-current-swatch";
    public static final String PREVIEW = "color-preview";
    public static final String PREVIEW_SWATCH = "color-preview-swatch";
    public static final String HEX = "color-hex";
    public static final String HEX_FIELD = "color-hex-field";
    public static final String HINT = "color-hint";
    public static final String FOOTER_RULE = "color-footer-rule";
    public static final String RESET = "color-reset";
    public static final String CANCEL = "color-cancel";
    public static final String SAVE = "color-save";

    /** How much of the popover survives a short window. */
    public enum Level { FULL, NO_SV, PRESETS_ONLY }

    public static final int WIDTH = 150;
    public static final int WIDTH_NARROW = 112;
    public static final int PAD = 6;
    public static final int TITLE_HEIGHT = 9;
    public static final int PRESET_SIZE = 14;
    public static final int PRESET_GAP = 3;
    public static final int SV_HEIGHT = 56;
    public static final int HUE_HEIGHT = 8;
    public static final int LINE_HEIGHT = 14;
    public static final int HINT_HEIGHT = 9;
    public static final int BUTTON_HEIGHT = 14;
    /** Full height at 100%: what the whole stack really needs, not the pre-narrow estimate. */
    public static final int FULL_HEIGHT = 192;
    public static final int NO_SV_HEIGHT = 132;
    public static final int PRESETS_ONLY_HEIGHT = 118;

    /**
     * What the popover has to say and how wide it is: the preset row belongs to the colour being
     * edited, and the three button labels have to be measured, not assumed.
     *
     * @param presets   the eight swatches of this colour
     * @param measure   raw glyph width of a label, i.e. the font the popover is drawn with
     * @param reset     "Reset" / "重置"
     * @param cancel    "Cancel" / "取消"
     * @param save      "Save" / "保存"
     */
    public record Content(List<Integer> presets, ToIntFunction<String> measure,
                          String reset, String cancel, String save) {
    }

    private final Map<String, Rect> rects = new LinkedHashMap<>();
    private final Map<String, Boolean> enabled = new LinkedHashMap<>();
    private Level level;
    private final boolean narrow;
    private final double scale;
    private final Content content;
    private Rect panel = Rect.EMPTY;
    private boolean buttonsStacked;

    private ColorPopupLayout(double scale, boolean narrow, Content content) {
        this.scale = scale;
        this.narrow = narrow;
        this.level = Level.FULL;
        this.content = content;
    }

    private int px(double design) {
        return Scale.effectivePx(design, scale);
    }

    /**
     * Builds the popover anchored to the gear popover's left edge, falling back to covering it when
     * there is no room beside it.
     */
    public static ColorPopupLayout compute(int guiWidth, int guiHeight, double scale, boolean narrow,
                                           Rect gearPopover, boolean saveEnabled, Content content) {
        ColorPopupLayout layout = new ColorPopupLayout(Scale.effective(scale), narrow, content);
        return layout.build(guiWidth, guiHeight, gearPopover, saveEnabled);
    }

    private ColorPopupLayout build(int guiWidth, int guiHeight, Rect gearPopover, boolean saveEnabled) {
        int pad = px(PAD);
        int width = px(narrow ? WIDTH_NARROW : WIDTH);
        int innerWidth = width - pad * 2;
        int presetsPerRow = narrow ? 4 : 8;
        int presetCount = content == null || content.presets() == null ? 0 : content.presets().size();
        int presetRows = Math.max(1, (presetCount + presetsPerRow - 1) / presetsPerRow);
        int presetsHeight = presetRows * px(PRESET_SIZE) + (presetRows - 1) * px(PRESET_GAP);
        boolean stackPair = narrow;

        int gap = px(2);
        int each = (innerWidth - gap * 2) / 3;
        buttonsStacked = !threeColumnsFit(each);
        int buttonsHeight = buttonsStacked ? px(BUTTON_HEIGHT) * 3 + gap * 2 : px(BUTTON_HEIGHT);

        // The three candidate heights are sums of the rows this layout would really emit.
        int fullHeight = pad + px(TITLE_HEIGHT) + px(4) + Scale.rule(scale) + px(5)
                + presetsHeight + px(6) + px(SV_HEIGHT) + px(4) + px(HUE_HEIGHT) + px(6)
                + pairHeight(stackPair) + px(4) + px(LINE_HEIGHT) + px(2) + px(HINT_HEIGHT)
                + px(4) + Scale.rule(scale) + px(5) + buttonsHeight + pad;
        int noSvHeight = fullHeight - px(SV_HEIGHT) - px(4);
        int bareHeight = noSvHeight - px(HUE_HEIGHT) - px(6);

        int anchorX = gearPopover.w() > 0 ? gearPopover.x() - px(4) - width : guiWidth - Scale.EDGE - width;
        int anchorY = gearPopover.w() > 0 ? gearPopover.y() : Scale.EDGE;
        boolean beside = anchorX >= Scale.EDGE;
        int x = beside ? anchorX : Math.max(Scale.EDGE, gearPopover.x());
        // The popover may cover other tactical panels - FLOAT is opaque for that - but never the
        // vanilla hotbar strip. When its own horizontal span reaches the hotbar, the strip's own
        // rectangle is the bottom bound; the popover is then moved up and degraded until it fits.
        int limit = bottomLimit(guiWidth, guiHeight, x, width);
        int y = Math.max(Scale.EDGE, Math.min(anchorY, limit - bareHeight));

        Level chosen = fullHeight <= limit - y ? Level.FULL
                : noSvHeight <= limit - y ? Level.NO_SV
                : Level.PRESETS_ONLY;
        this.level = chosen;
        boolean showSv = chosen == Level.FULL;
        boolean showHue = chosen != Level.PRESETS_ONLY;

        int cursor = y + pad;
        rects.put(TITLE, Rect.of(x + pad, cursor, innerWidth, px(TITLE_HEIGHT)));
        cursor += px(TITLE_HEIGHT) + px(4);
        rects.put(RULE, Rect.of(x + pad, cursor, innerWidth, Scale.rule(scale)));
        cursor += Scale.rule(scale) + px(5);
        int presetSize = px(PRESET_SIZE);
        int presetPitch = presetSize + px(PRESET_GAP);
        for (int index = 0; index < presetCount; index++) {
            int row = index / presetsPerRow;
            int column = index % presetsPerRow;
            rects.put(PRESET + index,
                    Rect.of(x + pad + column * presetPitch, cursor + row * presetPitch, presetSize, presetSize));
        }
        cursor += presetsHeight + px(6);
        if (showSv) {
            rects.put(SV, Rect.of(x + pad, cursor, innerWidth, px(SV_HEIGHT)));
            cursor += px(SV_HEIGHT) + px(4);
        }
        if (showHue) {
            rects.put(HUE, Rect.of(x + pad, cursor, innerWidth, px(HUE_HEIGHT)));
            cursor += px(HUE_HEIGHT) + px(6);
        }
        if (stackPair) {
            rects.put(CURRENT, Rect.of(x + pad, cursor, innerWidth, px(LINE_HEIGHT)));
            rects.put(CURRENT_SWATCH, Rect.of(x + pad + innerWidth - px(20), cursor + px(2), px(20), px(10)));
            cursor += px(LINE_HEIGHT) + px(2);
            rects.put(PREVIEW, Rect.of(x + pad, cursor, innerWidth, px(LINE_HEIGHT)));
            rects.put(PREVIEW_SWATCH, Rect.of(x + pad + innerWidth - px(20), cursor + px(2), px(20), px(10)));
            cursor += px(LINE_HEIGHT) + px(4);
        } else {
            int half = innerWidth / 2;
            rects.put(CURRENT, Rect.of(x + pad, cursor, half, px(LINE_HEIGHT)));
            rects.put(CURRENT_SWATCH, Rect.of(x + pad + half - px(24), cursor + px(2), px(20), px(10)));
            rects.put(PREVIEW, Rect.of(x + pad + half, cursor, innerWidth - half, px(LINE_HEIGHT)));
            rects.put(PREVIEW_SWATCH, Rect.of(x + pad + innerWidth - px(20), cursor + px(2), px(20), px(10)));
            cursor += px(LINE_HEIGHT) + px(4);
        }
        rects.put(HEX, Rect.of(x + pad, cursor, px(30), px(LINE_HEIGHT)));
        rects.put(HEX_FIELD, Rect.of(x + pad + innerWidth - px(66), cursor, px(66), px(LINE_HEIGHT)));
        cursor += px(LINE_HEIGHT) + px(2);
        rects.put(HINT, Rect.of(x + pad, cursor, innerWidth, px(HINT_HEIGHT)));
        cursor += px(HINT_HEIGHT) + px(4);
        rects.put(FOOTER_RULE, Rect.of(x + pad, cursor, innerWidth, Scale.rule(scale)));
        cursor += Scale.rule(scale) + px(5);
        if (buttonsStacked) {
            rects.put(RESET, Rect.of(x + pad, cursor, innerWidth, px(BUTTON_HEIGHT)));
            cursor += px(BUTTON_HEIGHT) + gap;
            rects.put(CANCEL, Rect.of(x + pad, cursor, innerWidth, px(BUTTON_HEIGHT)));
            cursor += px(BUTTON_HEIGHT) + gap;
            rects.put(SAVE, Rect.of(x + pad, cursor, innerWidth, px(BUTTON_HEIGHT)));
        } else {
            rects.put(RESET, Rect.of(x + pad, cursor, each, px(BUTTON_HEIGHT)));
            rects.put(CANCEL, Rect.of(x + pad + each + gap, cursor, each, px(BUTTON_HEIGHT)));
            rects.put(SAVE, Rect.of(x + pad + (each + gap) * 2, cursor, each, px(BUTTON_HEIGHT)));
        }
        cursor += px(BUTTON_HEIGHT) + pad;
        panel = Rect.of(x, y, width, cursor - y);
        enabled.put(SAVE, saveEnabled);
        return this;
    }

    /**
     * True when three equal columns can hold every label as written. The labels are measured with the
     * font the popover is drawn with, so an English "Cancel" at 32 pixels wide really does fail here
     * while the Chinese "取消" passes, and neither case depends on a hard-coded string length.
     */
    private boolean threeColumnsFit(int each) {
        if (content == null || content.measure() == null) return true;
        int budget = each - px(Motion.BUTTON_PAD_X) * 2;
        for (String label : new String[]{content.reset(), content.cancel(), content.save()}) {
            if (label == null) continue;
            if (TextMetrics.width(content.measure().applyAsInt(label), scale) > budget) return false;
        }
        return true;
    }

    /**
     * The lowest y this popover may reach.
     *
     * <p>It may cover other tactical panels - its base is opaque for exactly that - but the approved
     * rule is that no panel covers the vanilla hotbar strip. So when the popover's own horizontal span
     * reaches the hotbar's rectangle, that rectangle is the bottom bound; anywhere else the screen edge
     * is. The popover is moved up and degraded within that bound, never past it.</p>
     */
    private static int bottomLimit(int guiWidth, int guiHeight, int x, int width) {
        int hotbarLeft = guiWidth / 2 - Scale.AVOID_WIDTH / 2;
        int hotbarRight = hotbarLeft + Scale.AVOID_WIDTH;
        boolean overlapsHotbar = x < hotbarRight && hotbarLeft < x + width;
        if (!overlapsHotbar) return guiHeight - Scale.EDGE;
        return Math.min(guiHeight - Scale.EDGE, guiHeight - Scale.AVOID_HEIGHT);
    }

    private int pairHeight(boolean stacked) {
        return stacked ? px(LINE_HEIGHT) * 2 + px(2) : px(LINE_HEIGHT);
    }

    public Rect panel() {
        return panel;
    }

    public Rect rect(String id) {
        return rects.getOrDefault(id, Rect.EMPTY);
    }

    public Map<String, Rect> allRects() {
        return java.util.Collections.unmodifiableMap(rects);
    }

    public Level level() {
        return level;
    }

    public boolean showsSv() {
        return level == Level.FULL;
    }

    public boolean showsHue() {
        return level != Level.PRESETS_ONLY;
    }

    /** True when the three buttons had to be stacked because their labels do not fit three columns. */
    public boolean buttonsStacked() {
        return buttonsStacked;
    }

    public boolean saveEnabled() {
        return enabled.getOrDefault(SAVE, false);
    }

    /** The eight presets this popover draws, in the order their rectangles were emitted. */
    public List<Integer> presets() {
        return content == null || content.presets() == null ? List.of() : content.presets();
    }

    /** Every preset colour, for the drawing code that must not restate the palette. */
    public static List<Integer> presetsOf(ColorDraft.Kind kind) {
        return kind == ColorDraft.Kind.RANGE ? Palette.RANGE_PRESETS : Palette.THEME_PRESETS;
    }
}
