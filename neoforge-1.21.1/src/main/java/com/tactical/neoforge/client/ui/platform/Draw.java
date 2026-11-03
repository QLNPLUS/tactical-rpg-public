package com.tactical.neoforge.client.ui.platform;

import com.tactical.neoforge.client.UiPalette;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Slate;
import net.minecraft.client.gui.Font;
import com.tactical.neoforge.client.ui.debug.DrawnText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The only place that touches {@link GuiGraphics}. Every other UI class speaks GUI pixels and ARGB.
 *
 * <p>Allowed primitives are deliberately limited to {@code fill} / {@code drawString} / pose scaling /
 * scissor: no blur, no translucent full-screen darkening, no raw OpenGL, no shaders, no framebuffer,
 * no nine-slice stretching. Shapes are stepped-corner row fills so they stay crisp at every preset.</p>
 */
public final class Draw {

    private Draw() {
    }

    public static void fill(GuiGraphics graphics, Rect rect, int color) {
        if (rect == null || rect.w() <= 0 || rect.h() <= 0) return;
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), color);
    }

    public static void fill(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        graphics.fill(x, y, x + w, y + h, color);
    }

    /** Panel corner: row 0 inset 3, rows 1-2 inset 1, mirrored at the bottom. Insets never scale. */
    public static void slab(GuiGraphics graphics, Rect rect, int color) {
        stepped(graphics, rect, color, Scale.PANEL_CORNER);
    }

    /** Small part corner (rows, buttons, chips, icon boxes, tooltips): row 0 inset 2, row 1 inset 1. */
    public static void chip(GuiGraphics graphics, Rect rect, int color) {
        stepped(graphics, rect, color, Scale.SMALL_CORNER);
    }

    private static void stepped(GuiGraphics graphics, Rect rect, int color, int corner) {
        if (rect == null || rect.w() <= 0 || rect.h() <= 0) return;
        int w = rect.w();
        int h = rect.h();
        if (w <= corner * 2 + 2 || h <= corner * 2 + 2) {
            fill(graphics, rect, color);
            return;
        }
        int x = rect.x();
        int y = rect.y();
        // Top: narrowest row first, then the 1px inset band.
        fill(graphics, x + corner, y, w - corner * 2, 1, color);
        fill(graphics, x + 1, y + 1, w - 2, corner - 1, color);
        // Body.
        fill(graphics, x, y + corner, w, h - corner * 2, color);
        // Bottom mirror.
        fill(graphics, x + 1, y + h - corner, w - 2, corner - 1, color);
        fill(graphics, x + corner, y + h - 1, w - corner * 2, 1, color);
    }

    /** Hard shadow: the same shape, offset, drawn before the surface itself. Never hit-tested. */
    public static void shadow(GuiGraphics graphics, Rect rect, int offset, int color, boolean slab) {
        Rect moved = rect.move(offset, offset);
        if (slab) {
            slab(graphics, moved, color);
        } else {
            chip(graphics, moved, color);
        }
    }

    /**
     * The accent rule under a title: one pixel high (two past 150%), four equal hard segments, the
     * last one empty. Horizontal gradients are forbidden, so the segments are separate fills.
     */
    public static void accentRule(GuiGraphics graphics, Rect rect) {
        if (rect == null || rect.w() <= 0) return;
        int segment = rect.w() / 4;
        if (segment <= 0) {
            fill(graphics, rect, UiPalette.rule(0));
            return;
        }
        fill(graphics, rect.x(), rect.y(), segment, rect.h(), UiPalette.rule(0));
        fill(graphics, rect.x() + segment, rect.y(), segment, rect.h(), UiPalette.rule(1));
        fill(graphics, rect.x() + segment * 2, rect.y(), segment, rect.h(), UiPalette.rule(2));
    }

    public static void separator(GuiGraphics graphics, Rect rect) {
        fill(graphics, rect, Slate.LINE);
    }

    /**
     * An empty track with a filled portion.
     *
     * @param fraction 0..1 of the track that is filled from the left
     */
    public static void bar(GuiGraphics graphics, Rect track, int fillColor, double fraction) {
        fill(graphics, track, Slate.SUNK);
        int filled = (int) Math.round(track.w() * Math.max(0.0D, Math.min(1.0D, fraction)));
        if (filled <= 0) return;
        fill(graphics, track.x(), track.y(), filled, track.h(), fillColor);
    }

    /** A one-pixel tick inside a bar, used to show where the next AP recovery lands. */
    public static void tick(GuiGraphics graphics, Rect track, int x, int color, int width) {
        if (x <= track.x() || x >= track.right()) return;
        fill(graphics, x, track.y(), Math.min(width, track.right() - x), track.h(), color);
    }

    /**
     * A bar whose last stretch is the cost of the action about to be spent.
     *
     * <p>The pending segment is drawn over the filled part, from {@code pendingFrom} to
     * {@code fraction}, clamped into the bar: a cost larger than the current value may not paint
     * outside the track. It is a preview only - the server still decides whether the action is
     * affordable.</p>
     */
    public static void barPending(GuiGraphics graphics, Rect track, int fillColor, int pendingColor,
                                  double fraction, double pendingFrom) {
        fill(graphics, track, Slate.SUNK);
        double filled = Math.max(0.0D, Math.min(1.0D, fraction));
        int fillWidth = (int) Math.round(track.w() * filled);
        if (fillWidth > 0) fill(graphics, track.x(), track.y(), fillWidth, track.h(), fillColor);
        double from = Math.max(0.0D, Math.min(1.0D, pendingFrom));
        int fromWidth = (int) Math.round(track.w() * from);
        int pendingWidth = fillWidth - fromWidth;
        if (pendingWidth > 0) {
            fill(graphics, track.x() + fromWidth, track.y(), pendingWidth, track.h(), pendingColor);
        }
    }

    /** One-times text, always with the vanilla shadow, baseline at {@code y}. */
    public static void text(GuiGraphics graphics, Font font, String value, int x, int y, int color) {
        if (value == null || value.isEmpty()) return;
        // Development-only: the read-only probe reports which strings a frame really drew, and the width
        // the font measured for them. With the probe off this is one volatile load and no work.
        DrawnText.record(value, font, x, y);
        graphics.drawString(font, value, x, y, color, true);
    }

    /** Two-times text: only the panel title and the current AP value are ever drawn this big. */
    public static void text2x(GuiGraphics graphics, Font font, String value, int x, int y, int color) {
        if (value == null || value.isEmpty()) return;
        scaledText(graphics, font, value, x, y, 2.0D, color);
    }

    /**
     * Draws one floating layer on its own depth plane.
     *
     * <p>Drawing later is not the same as being in front: every {@code fill} and {@code drawString}
     * keeps the pose it was called with, and the HUD's lane portraits are real entity models pushed
     * 400 deep (plus the entity renderer's own 50). A tooltip painted after them at the default depth
     * is therefore still buried under a model that happens to stand where it lands.</p>
     *
     * <p>This submits everything drawn so far, raises the pose to {@code depth}, runs the layer,
     * submits that too, and restores the pose in a finally - so a layer that throws cannot leave the
     * pose or the pending batch in the raised plane. Like the screens' own overlays it never changes
     * what is drawn, only which plane it lands on.</p>
     */
    public static void atDepth(GuiGraphics graphics, float depth, Runnable layer) {
        if (graphics == null || layer == null) return;
        graphics.flush();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(0.0F, 0.0F, depth);
            layer.run();
        } finally {
            graphics.flush();
            pose.popPose();
        }
    }

    /** Text rendered at an arbitrary (integer multiple) scale, used by the size presets. */
    public static void scaledText(GuiGraphics graphics, Font font, String value, int x, int y, double scale,
                                  int color) {
        if (value == null || value.isEmpty()) return;
        if (Math.abs(scale - 1.0D) < 0.001D) {
            text(graphics, font, value, x, y, color);
            return;
        }
        // Scaled text bypasses the 1x path, so the probe is told about it here as well - after its own
        // enable check, so the measurement is only taken when somebody is going to read it.
        if (com.tactical.neoforge.client.ui.debug.DevProbe.enabled()) {
            DrawnText.recordMeasured(value, (int) Math.round(font.width(value) * scale), x, y);
        }
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0F);
        pose.scale((float) scale, (float) scale, 1.0F);
        graphics.drawString(font, value, 0, 0, color, true);
        pose.popPose();
    }

    public static void textRight(GuiGraphics graphics, Font font, String value, int right, int y, int color) {
        if (value == null || value.isEmpty()) return;
        text(graphics, font, value, right - font.width(value), y, color);
    }

    public static void textCentered(GuiGraphics graphics, Font font, String value, Rect area, int y, int color) {
        if (value == null || value.isEmpty()) return;
        text(graphics, font, value, area.x() + (area.w() - font.width(value)) / 2, y, color);
    }

    /** Truncates on the complete string and appends an ellipsis; never wraps. */
    public static String ellipsis(Font font, String value, int maxWidth) {
        if (value == null || value.isEmpty() || maxWidth <= 0) return "";
        if (font.width(value) <= maxWidth) return value;
        String ellipsis = "…";
        int budget = maxWidth - font.width(ellipsis);
        if (budget <= 0) return ellipsis;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char next = value.charAt(i);
            if (font.width(builder.toString() + next) > budget) break;
            builder.append(next);
        }
        return builder + ellipsis;
    }

    /** Draws text clipped to the given rectangle instead of shortening it. */
    public static void textClipped(GuiGraphics graphics, Font font, String value, Rect clip, int x, int y,
                                   int color) {
        if (value == null || value.isEmpty() || clip.w() <= 0) return;
        graphics.enableScissor(clip.x(), clip.y(), clip.right(), clip.bottom());
        try {
            text(graphics, font, value, x, y, color);
        } finally {
            graphics.disableScissor();
        }
    }

    /**
     * Runs one drawing block clipped to a rectangle, and always restores the clip afterwards.
     *
     * <p>This is the only sanctioned way to clip: a caller never touches {@code enableScissor} itself, so a
     * block that throws cannot leave the frame clipped for everything drawn after it. A degenerate rectangle
     * draws nothing at all instead of opening an empty clip.</p>
     */
    public static void scissor(GuiGraphics graphics, Rect clip, Runnable body) {
        if (graphics == null || body == null) return;
        if (clip == null || clip.w() <= 0 || clip.h() <= 0) return;
        graphics.enableScissor(clip.x(), clip.y(), clip.right(), clip.bottom());
        try {
            body.run();
        } finally {
            graphics.disableScissor();
        }
    }

    /**
     * A nine-by-nine gear drawn from pixels in the given colour: ring, four teeth and a hollow centre.
     * No texture, so it stays legible at every preset.
     */
    public static void gear(GuiGraphics graphics, Rect box, int color) {
        int size = Motion.GEAR_ICON;
        int x = box.x() + (box.w() - size) / 2;
        int y = box.y() + (box.h() - size) / 2;
        String[] rows = {
                "..#####..",
                ".#...#.#.",
                "..#...#..",
                "##.....##",
                "#.......#",
                "##.....##",
                "..#...#..",
                ".#...#.#.",
                "..#####..",
        };
        for (int row = 0; row < rows.length; row++) {
            String pattern = rows[row];
            for (int column = 0; column < pattern.length(); column++) {
                if (pattern.charAt(column) != '#') continue;
                fill(graphics, x + column, y + row, 1, 1, color);
            }
        }
    }

    /** Blends a colour towards a target by {@code progress}, used by the hover and press tweens. */
    public static int blend(int from, int to, double progress) {
        return Motion.lerpColor(from, to, progress);
    }

    // ---- picture symbols ----------------------------------------------------------------------------

    /**
     * One symbol drawn from a user-supplied picture instead of from a glyph.
     *
     * <p>The three character-page symbols ({@code −}, {@code +}, {@code ×}) are 8x8 RGBA files whose
     * artwork does not fill the canvas: the drawing occupies a rectangle inside it. That rectangle is
     * carried here, so the screen centres the <em>picture</em> rather than its transparent padding, and
     * no font metric is ever consulted - which is exactly the defect the glyph version had (a {@code −}
     * measured by the font sits on the text baseline, not in the middle of its button).</p>
     *
     * @param u      left texel of the artwork inside the canvas
     * @param v      top texel of the artwork inside the canvas
     * @param w      width of the artwork in texels
     * @param h      height of the artwork in texels
     * @param canvas edge length of the square source file
     */
    public record Sprite(ResourceLocation texture, int u, int v, int w, int h, int canvas) { }

    private static final String ICON_DIR = "textures/gui/icons/";

    /** The user's minus picture: a five-texel bar, second row of its canvas. */
    public static final Sprite ICON_MINUS = sprite("character_minus", 0, 2, 5, 1);
    /** The user's plus picture: a five-texel cross in the canvas' top-left corner. */
    public static final Sprite ICON_PLUS = sprite("character_plus", 0, 0, 5, 5);
    /** The user's close picture: a five-texel X in the canvas' top-left corner. */
    public static final Sprite ICON_CLOSE = sprite("character_close", 0, 0, 5, 5);

    private static Sprite sprite(String name, int u, int v, int w, int h) {
        return new Sprite(ResourceLocation.fromNamespaceAndPath("tactical", ICON_DIR + name + ".png"),
                u, v, w, h, 8);
    }

    /**
     * One symbol's artwork placed inside a box, in GUI pixels.
     *
     * <p>{@code x} and {@code y} may carry a half pixel on purpose: the artwork's <em>centre</em> is the
     * box's centre. Truncating the leftover space to whole pixels instead - the previous behaviour - pushed
     * symbols with odd leftover space half a pixel towards the top left, including the actual 10 px
     * character controls at 100% containing 5 px artwork, which is the reported off-centre case.</p>
     *
     * <p>{@code w} and {@code h} stay a whole multiple of the artwork, and never larger than its canvas, so
     * every source texel keeps the same width and the picture is never stretched.</p>
     */
    public record SpritePlacement(double x, double y, int w, int h, int factor) {

        public double centreX() { return x + w / 2.0D; }

        public double centreY() { return y + h / 2.0D; }

        /** The whole-pixel rectangle the placement covers, for reporting and hit checks. */
        public Rect pixelRect() {
            return w <= 0 || h <= 0 ? Rect.EMPTY : Rect.of((int) Math.round(x), (int) Math.round(y), w, h);
        }
    }

    /**
     * Where a symbol's artwork lands inside a box: the largest whole-pixel multiple of the artwork that fits
     * with a one-pixel margin, centred on the box's own centre.
     *
     * <p>Pure arithmetic, so "the picture is centred" is checked without a window, and the drawing uses this
     * very placement. An integer multiple keeps every source texel a whole number of GUI pixels wide.</p>
     */
    public static SpritePlacement spritePlacement(Sprite sprite, Rect box) {
        if (sprite == null || box == null || sprite.w() <= 0 || sprite.h() <= 0
                || box.w() <= 0 || box.h() <= 0) {
            return new SpritePlacement(0.0D, 0.0D, 0, 0, 0);
        }
        int factor = Math.max(1, Math.min((box.w() - 2) / sprite.w(), (box.h() - 2) / sprite.h()));
        factor = Math.min(factor, Math.max(1, sprite.canvas()));
        int w = sprite.w() * factor;
        int h = sprite.h() * factor;
        return new SpritePlacement(box.x() + (box.w() - w) / 2.0D, box.y() + (box.h() - h) / 2.0D, w, h,
                factor);
    }

    /**
     * The same placement with its origin snapped onto whole {@code framebuffer} pixels.
     *
     * <p>A half pixel is a whole screen pixel only when the GUI scale is even. Snapping the origin to the
     * monitor's own pixel grid keeps every texel edge on a screen pixel at any GUI scale, and leaves the
     * artwork at most half a <em>screen</em> pixel off the box's centre - invisible, and never the systematic
     * one-pixel imbalance of the truncated placement. A missing or non-finite scale is treated as 1.</p>
     */
    public static SpritePlacement spritePlacement(Sprite sprite, Rect box, double framebuffer) {
        SpritePlacement exact = spritePlacement(sprite, box);
        if (exact.w() <= 0 || exact.h() <= 0) return exact;
        double scale = Double.isFinite(framebuffer) && framebuffer > 0.0D ? framebuffer : 1.0D;
        return new SpritePlacement(snap(exact.x(), scale), snap(exact.y(), scale), exact.w(), exact.h(),
                exact.factor());
    }

    private static double snap(double guiValue, double framebuffer) {
        return Math.round(guiValue * framebuffer) / framebuffer;
    }

    /** The current window's GUI (framebuffer) scale, or 1 when it cannot be read. */
    public static double framebufferScale() {
        try {
            var window = net.minecraft.client.Minecraft.getInstance().getWindow();
            double scale = window == null ? 1.0D : window.getGuiScale();
            return Double.isFinite(scale) && scale > 0.0D ? scale : 1.0D;
        } catch (RuntimeException | LinkageError unavailable) {
            // Outside a running client there is no window to ask; a whole GUI pixel is the safe grid.
            return 1.0D;
        }
    }

    /**
     * Draws one picture symbol, tinted by {@code color} and pixel aligned.
     *
     * <p>The white artwork is tinted through {@code GuiGraphics}' own colour, which is restored in a
     * {@code finally}: a symbol that throws may not leave the tint applied to the item icons, the HUD or
     * anything else drawn afterwards. The same goes for the pose: it is pushed, scaled by a whole number
     * and popped again, and nothing here reads a font metric.</p>
     */
    public static void sprite(GuiGraphics graphics, Sprite sprite, Rect box, int color) {
        if (graphics == null || sprite == null) return;
        SpritePlacement target = spritePlacement(sprite, box, framebufferScale());
        if (target.w() <= 0 || target.h() <= 0 || target.factor() <= 0) return;
        graphics.flush();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate((float) target.x(), (float) target.y(), 0.0F);
            pose.scale(target.factor(), target.factor(), 1.0F);
            graphics.setColor(((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F,
                    (color & 0xFF) / 255.0F, ((color >>> 24) & 0xFF) / 255.0F);
            graphics.blit(sprite.texture(), 0, 0, sprite.u(), sprite.v(), sprite.w(), sprite.h(),
                    sprite.canvas(), sprite.canvas());
        } finally {
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            pose.popPose();
        }
    }

    /**
     * A filled stepped circle, used for the round bases of the {@code −}/{@code +} controls.
     *
     * <p>Rows are inset by the circle's own quarter-profile, so the shape stays symmetric and crisp at
     * every preset: the enabled and the disabled state draw exactly the same base and only the symbol
     * changes colour, per the frozen rule that "available" is never shown by the shape of the base.</p>
     */
    public static void disc(GuiGraphics graphics, Rect rect, int color) {
        if (rect == null || rect.w() <= 0 || rect.h() <= 0) return;
        int h = rect.h();
        double radius = h / 2.0D;
        for (int row = 0; row < h; row++) {
            double dy = row + 0.5D - radius;
            double span = Math.sqrt(Math.max(0.0D, radius * radius - dy * dy));
            int inset = (int) Math.round(radius - span);
            int width = rect.w() - inset * 2;
            if (width <= 0) continue;
            fill(graphics, rect.x() + inset, rect.y() + row, width, 1, color);
        }
    }
}
