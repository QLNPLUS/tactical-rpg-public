package com.tactical.neoforge.client.ui.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wording of the colour settings, as translation keys plus the Chinese fallback.
 *
 * <p>Pure on purpose: the key list and the fallbacks are data, so a test can prove that every key is
 * really in both language files and that the English labels are the ones the layout was measured
 * against. Resolving a key goes through {@link ColorTextResolver}, which is the only part that needs
 * a running client.</p>
 *
 * <p>Only the strings this batch introduces live here. The older panel wording keeps its existing
 * literals: reworking every player-facing string is batch 4's job, not this one's.</p>
 */
public final class ColorText {

    public static final String GEAR_THEME = "tactical.ui.gear.theme";
    public static final String GEAR_RANGE = "tactical.ui.gear.range";
    public static final String GEAR_RESET_SIZES = "tactical.ui.gear.reset_sizes";

    public static final String THEME_TITLE = "tactical.ui.color.theme_title";
    public static final String RANGE_TITLE = "tactical.ui.color.range_title";
    public static final String CURRENT = "tactical.ui.color.current";
    public static final String PREVIEW = "tactical.ui.color.preview";
    public static final String HEX = "tactical.ui.color.hex";
    public static final String HINT = "tactical.ui.color.hint";
    public static final String HINT_ERROR = "tactical.ui.color.hint_error";
    public static final String RESET = "tactical.ui.color.reset";
    public static final String CANCEL = "tactical.ui.color.cancel";
    public static final String SAVE = "tactical.ui.color.save";

    /** Every key this batch adds; the test walks this list against both language files. */
    public static final List<String> KEYS = List.of(GEAR_THEME, GEAR_RANGE, GEAR_RESET_SIZES,
            THEME_TITLE, RANGE_TITLE, CURRENT, PREVIEW, HEX, HINT, HINT_ERROR, RESET, CANCEL, SAVE);

    /**
     * The English labels the popover is measured with, kept next to the keys so the layout test can
     * feed the longest real label to the button-column decision without a client running.
     */
    public static final String EN_CANCEL = "Cancel";
    public static final String EN_SAVE = "Save";
    public static final String EN_RESET = "Reset";

    private static final Map<String, String> FALLBACKS = fallbacks();

    private ColorText() {
    }

    private static Map<String, String> fallbacks() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(GEAR_THEME, "主题色");
        map.put(GEAR_RANGE, "射程颜色");
        map.put(GEAR_RESET_SIZES, "HUD 尺寸恢复默认");
        map.put(THEME_TITLE, "主题色");
        map.put(RANGE_TITLE, "射程颜色");
        map.put(CURRENT, "当前");
        map.put(PREVIEW, "预览");
        map.put(HEX, "HEX");
        map.put(HINT, "6 位十六进制，例如 C4473D");
        map.put(HINT_ERROR, "格式应为 6 位 0–9 / A–F");
        map.put(RESET, "重置");
        map.put(CANCEL, "取消");
        map.put(SAVE, "保存");
        return java.util.Collections.unmodifiableMap(map);
    }

    /** What a key says when nothing resolves it, so the popover is never drawn blank. */
    public static String fallback(String key) {
        String value = FALLBACKS.get(key);
        return value == null ? key : value;
    }

    /** The fallback of the popover a colour opens, i.e. its own title. */
    public static String titleKey(ColorDraft.Kind kind) {
        return kind == ColorDraft.Kind.RANGE ? RANGE_TITLE : THEME_TITLE;
    }

    public static String gearKey(ColorDraft.Kind kind) {
        return kind == ColorDraft.Kind.RANGE ? GEAR_RANGE : GEAR_THEME;
    }

    /**
     * The wording key of one of the popover's three buttons.
     *
     * <p>A control id and the text drawn on it are two different things, and drawing the id is what
     * turned "Reset / Cancel / Save" into "color-reset / color-cancel / color-save". This mapping is
     * the only place the two are connected, so a button can never be given its own id as its label.</p>
     */
    public static String buttonKey(String controlId) {
        return switch (controlId) {
            case ColorPopupLayout.RESET -> RESET;
            case ColorPopupLayout.CANCEL -> CANCEL;
            case ColorPopupLayout.SAVE -> SAVE;
            default -> throw new IllegalArgumentException("no wording for the control " + controlId);
        };
    }
}
