package com.tactical.neoforge.client.ui.panel;

import com.tactical.neoforge.client.ui.layout.Box;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Slate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared skeleton of every replacing panel: right edge, six pixels from the top, one of two
 * widths, title row, accent rule, content, footer.
 *
 * <p>Pure, like the command panel's own layout: a builder walks the vertical cursor once and records
 * every rectangle, so drawing, hit testing and the probe read the same numbers. A page only adds its
 * own content rows; it can never invent a different padding, gap or rule position.</p>
 */
public final class PanelLayout {

    public static final String PANEL = "panel";
    public static final String TITLE = "title";
    public static final String RULE = "rule";
    public static final String FOOTER_RULE = "footer-rule";
    public static final String FOOTER = "footer";

    /** Gap above a separator line, and below it. */
    private static final int GROUP_ABOVE = 4;
    private static final int GROUP_BELOW = 5;

    private final int guiWidth;
    private final int guiHeight;
    private final double scale;
    private final boolean narrow;
    private final int width;
    private final int x0;
    private final int pad;
    private final int innerX;
    private final int innerWidth;

    private final Map<String, Rect> rects = new LinkedHashMap<>();
    private final List<Box> boxes = new ArrayList<>();
    private int y;
    private Rect panel = Rect.EMPTY;
    private boolean footerStacked;

    private PanelLayout(int guiWidth, int guiHeight, double scale, boolean narrow) {
        this.guiWidth = guiWidth;
        this.guiHeight = guiHeight;
        this.scale = Scale.effective(scale);
        this.narrow = narrow;
        this.pad = Scale.effectivePx(Motion.PAD, this.scale);
        this.width = Scale.effectivePx(narrow ? Motion.PANEL_WIDTH_NARROW : Motion.PANEL_WIDTH, this.scale);
        this.x0 = guiWidth - Scale.EDGE - width;
        this.innerX = x0 + pad;
        this.innerWidth = Math.max(1, width - pad * 2);
        this.y = Scale.EDGE + pad;
    }

    public static PanelLayout begin(int guiWidth, int guiHeight, double scale, boolean narrow) {
        return new PanelLayout(guiWidth, guiHeight, scale, narrow);
    }

    // ------------------------------------------------------------------ scale

    public double scale() {
        return scale;
    }

    public boolean narrow() {
        return narrow;
    }

    public int px(double design) {
        return Scale.effectivePx(design, scale);
    }

    public int innerX() {
        return innerX;
    }

    public int innerWidth() {
        return innerWidth;
    }

    public int cursor() {
        return y;
    }

    public void moveTo(int y) {
        this.y = y;
    }

    public int guiHeight() {
        return guiHeight;
    }

    /** Space from the cursor to the safe bottom of the screen, hotbar avoidance included. */
    public int budgetToBottom() {
        int hotbar = guiHeight - Scale.AVOID_HEIGHT;
        int left = x0;
        int right = x0 + width;
        int avoidLeft = guiWidth / 2 - Scale.AVOID_WIDTH / 2;
        int avoidRight = avoidLeft + Scale.AVOID_WIDTH;
        int limit = right > avoidLeft && left < avoidRight ? hotbar : guiHeight - Scale.EDGE - pad;
        return Math.max(0, limit - y);
    }

    // ------------------------------------------------------------------ blocks

    public void gap(double design) {
        y += px(design);
    }

    /** A full-width block at the cursor; the caller draws into it. */
    public Rect block(String id, double designHeight) {
        Rect rect = Rect.of(innerX, y, innerWidth, px(designHeight));
        rects.put(id, rect);
        y += rect.h();
        return rect;
    }

    /** The title row, drawn one text line high unless the page needs a taller header. */
    public Rect title() {
        return block(TITLE, Motion.LINE);
    }

    /** The accent rule under a title: four pixels above it and five below. */
    public void rule() {
        gap(GROUP_ABOVE);
        Rect rect = Rect.of(innerX, y, innerWidth, Scale.rule(scale));
        rects.put(RULE, rect);
        y += rect.h() + GROUP_BELOW;
    }

    /**
     * One footer row: a separator with four pixels above and five below, then the buttons.
     *
     * <p>The widths are the measured ones, so an English label gets the room it needs. When the row does
     * not fit the panel's inner width, the buttons stack vertically at full inner width instead of being
     * cut down: that is the degradation the visual specification allows, and it never shrinks a label.</p>
     *
     * @param ids    button ids, laid out right to left in the order given left to right
     * @param widths measured widths in GUI pixels
     */
    public void footer(List<String> ids, List<Integer> widths, double designHeight) {
        gap(GROUP_ABOVE);
        Rect rule = Rect.of(innerX, y, innerWidth, Scale.rule(scale));
        rects.put(FOOTER_RULE, rule);
        y += rule.h() + GROUP_BELOW;
        int gap = px(Motion.ROW_GAP);
        int total = 0;
        for (int width : widths) total += width;
        total += gap * Math.max(0, ids.size() - 1);
        if (total > innerWidth) {
            // Vertical: every button takes the whole inner width, in the same order, same gaps. A single
            // button wider than the panel takes that width too - otherwise the one label that cannot fit
            // would be the only one drawn outside its own panel.
            footerStacked = true;
            for (int index = 0; index < ids.size(); index++) {
                Rect button = Rect.of(innerX, y, innerWidth, px(designHeight));
                rects.put(ids.get(index), button);
                interactive(ids.get(index), button);
                y += button.h() + gap;
            }
            y -= gap;
            return;
        }
        int x = innerX + Math.max(0, innerWidth - total);
        Rect footer = Rect.of(innerX, y, innerWidth, px(designHeight));
        rects.put(FOOTER, footer);
        for (int index = 0; index < ids.size(); index++) {
            Rect button = Rect.of(x, y, widths.get(index), footer.h());
            rects.put(ids.get(index), button);
            interactive(ids.get(index), button);
            x += button.w() + gap;
        }
        y += footer.h();
    }

    /** True when {@link #footer} had to stack the buttons vertically in this layout. */
    public boolean footerStacked() {
        return footerStacked;
    }

    /** Marks a rectangle as a hit target, in the order the page wants them tried (highest first). */
    public PanelLayout interactive(String id, Rect rect) {
        rects.put(id, rect);
        boxes.add(Box.of(id, rect));
        return this;
    }

    public PanelLayout disabled(String id, Rect rect) {
        rects.put(id, rect);
        boxes.add(Box.of(id, rect).disabled());
        return this;
    }

    public PanelLayout display(String id, Rect rect) {
        rects.put(id, rect);
        return this;
    }

    /** Closes the panel around everything recorded so far. */
    public PanelLayout finish() {
        panel = Rect.of(x0, Scale.EDGE, width, Math.max(1, y + pad - Scale.EDGE));
        return this;
    }

    public Rect panel() {
        return panel;
    }

    public Rect rect(String id) {
        return rects.getOrDefault(id, Rect.EMPTY);
    }

    /** Hit order: the page's own controls, highest layer first. */
    public List<Box> hitOrder() {
        return boxes;
    }

    public Map<String, Rect> allRects() {
        return Collections.unmodifiableMap(rects);
    }

    public boolean fitsVertically() {
        return panel.bottom() <= guiHeight - Scale.EDGE;
    }

    /** Theme entry point so every page speaks the same token for a small-part surface. */
    public static int surface() {
        return Slate.RAISE;
    }
}
