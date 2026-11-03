package com.tactical.neoforge.client.ui.command;

import com.tactical.neoforge.client.UiPalette;
import com.tactical.neoforge.client.BattleClientState;
import com.tactical.neoforge.client.BattleMenu;
import com.tactical.neoforge.client.BattleScreenInput;
import com.tactical.neoforge.client.UiZoom;
import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.neoforge.client.ui.layout.Box;
import com.tactical.neoforge.client.ui.layout.HitTest;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.platform.Draw;
import com.tactical.neoforge.client.ui.panel.TooltipLayer;
import com.tactical.neoforge.client.ui.screen.NativeTacticalScreen;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Palette;
import com.tactical.neoforge.client.ui.theme.Slate;
import com.tactical.neoforge.client.ui.widget.PopupStack;
import com.tactical.neoforge.client.ui.widget.Tween;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native command panel: the pilot screen of the UI remake.
 *
 * <p>It keeps the previous behaviour exactly - one panel per decision point, the same eight actions
 * backed by the same callbacks, auto close and re-open on round, action, movement and window change -
 * and only replaces how it is drawn and hit-tested. Popovers live on their own layer, so Escape
 * closes the settings popover before it closes the panel.</p>
 */
public final class BattleMenuScreen extends NativeTacticalScreen {

    public static final String SCREEN_ID = "battle_menu";
    private static final String SETTINGS = "settings";
    private static final int TEXT_LINE = 9;

    private final PopupStack popups = new PopupStack();
    private final ColorSettingsPanel colors = new ColorSettingsPanel();
    private final Tween tween = new Tween();
    private CommandMenuLayout layout;
    private CommandMenuModel model;
    private double builtScale = -1;
    private int builtWidth = -1;
    private int builtHeight = -1;
    private final long openedAt;
    private long lastFrame;
    private final com.tactical.neoforge.client.ui.widget.PointerCapture capture = new com.tactical.neoforge.client.ui.widget.PointerCapture();
    private String hoveredId;
    /**
     * The action viewport's scroll offset, in whole rows.
     *
     * <p>It survives a rebuild (a preset change, a window resize, a popover opening) so the rows do not
     * jump under the pointer, and it is clamped on every rebuild so a smaller window can never leave a
     * blank tail. A new panel session starts at the top, like every other list in this interface.</p>
     */
    private int scroll;
    private int mouseX;
    private int mouseY;
    /** Weapon line of the last frame: full text, what was drawn, and whether it had to be cut. */
    private String weaponFull = "";
    private String weaponShown = "";
    private boolean weaponTruncated;
    /** Entrance alpha of the last frame, reported by the probe so a faded frame is never mistaken. */
    private double lastAlpha = 1.0D;
    /** Tooltip box and lines of the last frame, empty when no tooltip was drawn. */
    private com.tactical.neoforge.client.ui.layout.Rect tooltipBox =
            com.tactical.neoforge.client.ui.layout.Rect.EMPTY;
    private java.util.List<String> tooltipLines = java.util.List.of();
    /** The two vitals values of the last frame: full text, what was drawn, and whether it was cut. */
    private com.tactical.neoforge.client.ui.layout.TextMetrics.Fitted hpText =
            new com.tactical.neoforge.client.ui.layout.TextMetrics.Fitted("", "", false);
    private com.tactical.neoforge.client.ui.layout.TextMetrics.Fitted mpText =
            new com.tactical.neoforge.client.ui.layout.TextMetrics.Fitted("", "", false);
    /** The single-character popover buttons draw these ASCII signs; never folded to an ellipsis. */
    static final String POP_MINUS_LABEL = "-";
    static final String POP_PLUS_LABEL = "+";
    /** Last frame's row texts, for the probe only: full description, drawn description, name. */
    private final Map<String, String[]> rowText = new java.util.LinkedHashMap<>();
    /** The 2x panel title as it was measured this frame, so a cut title can hang its whole wording. */
    private String titleFull = "";
    private boolean titleTruncated;
    /** The footer line as worded, and whether the drawn line is shorter than it. */
    private String footerFull = "";
    private boolean footerTruncated;

    public BattleMenuScreen() {
        this.openedAt = Util.getMillis();
        this.lastFrame = openedAt;
    }

    @Override
    public String screenId() {
        return SCREEN_ID;
    }

    @Override
    protected void rebuild() {
        double scale = UiZoom.zoom();
        // The row list follows the commanded actor: a companion turn draws its five orders, and the panel
        // height follows the shorter list.
        layout = CommandMenuLayout.compute(width, height, scale, !popups.isEmpty(), textFloor(), UiZoom.percent(),
                !UiZoom.hudSizesAreDefault(), scroll, actionsForFrame());
        // The layout clamps the offset to what really fits; keeping the clamped value means the next
        // frame and the wheel chain start from the same position.
        scroll = layout.firstRow();
        if (colors.active()) {
            colors.ensure(width, height, scale, layout.narrow(), layout.rect(CommandMenuLayout.POPOVER), this.font);
        }
        builtScale = scale;
        builtWidth = width;
        builtHeight = height;
    }

    /**
     * The smallest scale this frame may draw text at: the smallest preset, raised to 1.0 when a GUI
     * scale of 1 would put a glyph below one screen pixel. The saved preset is never rewritten.
     */
    private double textFloor() {
        double guiScale = minecraft == null || minecraft.getWindow() == null ? 2.0D
                : Math.max(1.0D, minecraft.getWindow().getGuiScale());
        return Math.max(Scale.MIN_EFFECTIVE, 1.0D / guiScale);
    }

    private void ensureLayout() {
        double scale = UiZoom.zoom();
        if (layout == null || builtWidth != width || builtHeight != height
                || Math.abs(builtScale - scale) > 1.0E-4D) {
            rebuild();
        }
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void drawContent(GuiGraphics graphics, int pointerX, int pointerY, float partialTick) {
        ensureLayout();
        if (minecraft != null && !minecraft.isWindowActive()) capture.cancel();
        mouseX = pointerX;
        mouseY = pointerY;
        model = CommandMenuModel.snapshot(minecraft);
        long now = Util.getMillis();
        double delta = Math.max(0.0D, Math.min(100.0D, now - lastFrame));
        lastFrame = now;

        Box hit = HitTest.topmost(layout.hitOrder(), pointerX, pointerY);
        String hover = hit == null ? null : hit.id();
        if (hover == null) hover = passiveHover(pointerX, pointerY);
        hoveredId = hover;
        tween.hovered(hoveredId, now);

        double enter = Motion.easeOutCubic(Motion.progress(now - openedAt, Motion.PANEL_ENTER_MS));
        lastAlpha = enter;
        int rise = (int) Math.round(3.0D * (1.0D - enter));
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, rise, 0.0F);
        try {
            drawPanel(graphics, now, delta, enter);
            drawRows(graphics, now, delta, enter);
            drawFooter(graphics, enter);
            if (!popups.isEmpty()) {
                graphics.flush();
                graphics.pose().pushPose();
                graphics.pose().translate(0, 0, 300);
                drawPopover(graphics, now, delta, enter);
                graphics.flush();
                graphics.pose().popPose();
            }
            if (colors.active()) {
                // The colour popover is a modal layer: its geometry and hover are updated here, but it
                // is painted in renderTopOverlay, i.e. after the battle HUD, so the roster and the
                // charge rail can never cover it.
                colors.ensure(width, height, layout.scale(), layout.narrow(),
                        layout.rect(CommandMenuLayout.POPOVER), this.font);
                colors.hover(pointerX, pointerY);
            }
            // The HUD paints after this content, so the tooltip belongs to renderTopOverlay and is only
            // drawn here when no HUD is drawn at all: exactly one pass draws it.
            if (TooltipLayer.forFrame(hudHidden()) == TooltipLayer.CONTENT) drawTooltip(graphics, now);
        } finally {
            graphics.pose().popPose();
        }
    }

    private void drawPanel(GuiGraphics graphics, long now, double delta, double alpha) {
        Font font = this.font;
        double scale = layout.scale();
        String apLabel = ClientText.get(ClientText.Keys.COMMAND_AP_LABEL);
        Rect panel = layout.panel();
        Draw.shadow(graphics, panel, Scale.PANEL_SHADOW, col(Slate.SHADOW, alpha), true);
        Draw.slab(graphics, panel, col(Slate.SLAB, alpha));

        Rect title = layout.rect(CommandMenuLayout.TITLE);
        if (layout.narrow()) {
            // The small window turns the header into the actor line and keeps the gear beside it.
            int gearWidth = Scale.effectivePx(Motion.GEAR, scale);
            int chipWidth = model.companion ? chipWidth(font, model.identityChip, scale) : 0;
            int headerBudget = Math.max(1, title.w() - gearWidth - Scale.effectivePx(4, scale)
                    - (chipWidth > 0 ? chipWidth + Scale.effectivePx(4, scale) : 0));
            String header = ellipsis(font, model.companion ? model.actor : model.actor + " · " + model.round,
                    headerBudget);
            text(graphics, font, header, title.x(), baseline(title, scale), col(Slate.TEXT, alpha));
            if (chipWidth > 0) {
                drawIdentityChip(graphics, font, scale, alpha, title.x() + headerBudget + Scale.effectivePx(4, scale),
                        title.y() + (title.h() - Scale.effectivePx(9, scale)) / 2);
            }
            titleFull = "";
            titleTruncated = false;
        } else {
            // The title is the only 2x text in the panel: it is measured at 2x against the row that has
            // to hold it beside the gear, and shortened with an ellipsis when the language is wider than
            // the fixed 162 panel. The whole wording then hangs on the title's tooltip.
            titleFull = ClientText.get(ClientText.Keys.COMMAND_TITLE);
            int titleBudget = Math.max(1, title.w() - Scale.effectivePx(Motion.GEAR, scale)
                    - Scale.effectivePx(4, scale));
            String titleShown = com.tactical.neoforge.client.ui.layout.TextMetrics.ellipsis(
                    titleFull, titleBudget, 2.0D * scale, font::width);
            titleTruncated = !titleShown.equals(titleFull);
            Draw.scaledText(graphics, font, titleShown, title.x(), baseline(title, 2.0D * scale), 2.0D * scale,
                    col(Slate.TEXT, alpha));
        }

        Rect gear = layout.rect(CommandMenuLayout.GEAR);
        boolean gearHovered = CommandMenuLayout.GEAR.equals(hoveredId);
        double gearProgress = tween.progress(CommandMenuLayout.GEAR, gearHovered, now, Motion.HOVER_MS, delta);
        boolean gearOpen = !popups.isEmpty();
        int gearBase = gearOpen ? Slate.SUNK : Draw.blend(Slate.RAISE, Slate.HOVER, gearProgress);
        Draw.chip(graphics, gear, col(gearBase, alpha));
        Draw.gear(graphics, gear, col(gearOpen || gearHovered ? Slate.TEXT : Slate.TEXT2, alpha));

        Draw.accentRule(graphics, layout.rect(CommandMenuLayout.RULE));

        Rect actor = layout.rect(CommandMenuLayout.ACTOR);
        if (actor.w() > 0) {
            int chipWidth = model.companion ? chipWidth(font, model.identityChip, scale) : 0;
            int textBudget = Math.max(1, actor.w() - (chipWidth > 0 ? chipWidth + Scale.effectivePx(4, scale) : 0));
            String actorText = ellipsis(font, model.actor + " · " + model.round, textBudget);
            text(graphics, font, actorText, actor.x(), baseline(actor, scale), col(Slate.TEXT, alpha));
            // The identity chip is never shortened: the name gives way instead, and its full wording stays
            // on the existing tooltip of that row.
            if (chipWidth > 0) drawIdentityChip(graphics, font, scale, alpha,
                    actor.right() - chipWidth, actor.y() + (actor.h() - Scale.effectivePx(9, scale)) / 2);
        }

        Rect ap = layout.rect(CommandMenuLayout.AP_VALUE);
        if (layout.narrow()) {
            int lineY = baseline(ap, scale);
            text(graphics, font, apLabel, ap.x(), lineY, col(Slate.TEXT2, alpha));
            int maxWidthNarrow = textWidth(font, model.apMax);
            textRight(graphics, font, model.apMax, ap.right(), lineY, col(Slate.TEXT2, alpha));
            // Same single space between the current value and "/ max" as the vitals line below.
            textRight(graphics, font, model.ap, ap.right() - maxWidthNarrow - textWidth(font, " "), lineY,
                    col(Slate.AP, alpha));
        } else {
            int apBottom = ap.y() + (ap.h() + (int) Math.round(Motion.LINE_2X * scale)) / 2;
            int maxWidth = textWidth(font, model.apMax);
            text(graphics, font, apLabel, ap.x(), apBottom - Scale.effectivePx(TEXT_LINE, scale), col(Slate.TEXT2, alpha));
            textRight(graphics, font, model.apMax, ap.right(), apBottom - Scale.effectivePx(TEXT_LINE, scale), col(Slate.TEXT2, alpha));
            int apValueWidth = (int) Math.ceil(font.width(model.ap) * 2.0D * scale);
            Draw.scaledText(graphics, font, model.ap, ap.right() - maxWidth - Scale.effectivePx(4, scale) - apValueWidth,
                    apBottom - (int) Math.round(Motion.LINE_2X * scale), 2.0D * scale, col(Slate.AP, alpha));
        }

        Rect apBar = layout.rect(CommandMenuLayout.AP_BAR);
        Draw.bar(graphics, apBar, col(Slate.AP, alpha), model.apFraction);
        int recovery = model.apRecovery;
        int apMax = Math.max(1, BattleClientState.maxAp);
        if (recovery > 0 && apMax > recovery) {
            for (int value = recovery; value < apMax; value += recovery) {
                int tickX = apBar.x() + (int) Math.round(apBar.w() * (value / (double) apMax));
                Draw.tick(graphics, apBar, tickX, col(Slate.SLAB, alpha), Scale.rule(scale));
            }
        }

        hpText = drawGauge(graphics, layout.rect(CommandMenuLayout.HP_VALUE), layout.rect(CommandMenuLayout.HP_BAR),
                ClientText.get(layout.narrow() ? ClientText.Keys.COMMAND_HP_LABEL
                        : ClientText.Keys.COMMAND_HP_LABEL_SHORT), model.hp, model.hpCompact, model.hpFraction,
                Slate.HP, alpha);
        mpText = drawGauge(graphics, layout.rect(CommandMenuLayout.MP_VALUE), layout.rect(CommandMenuLayout.MP_BAR),
                ClientText.get(layout.narrow() ? ClientText.Keys.COMMAND_MP_LABEL
                        : ClientText.Keys.COMMAND_MP_LABEL_SHORT), model.mp, model.mpCompact, model.mpFraction,
                Slate.MP, alpha);
        drawWeapon(graphics, alpha);
    }

    /**
     * The persistent weapon line of the standard form: a value row like the AP row - left label in
     * secondary text, value right-aligned in primary text, no background, no hover state, no icon.
     * The narrow form drops it; there the attack row's tooltip carries the same wording instead.
     */
    private void drawWeapon(GuiGraphics graphics, double alpha) {
        Rect row = layout.rect(CommandMenuLayout.WEAPON);
        weaponFull = "";
        weaponShown = "";
        weaponTruncated = false;
        if (row.h() <= 0) return;
        Font font = this.font;
        double scale = layout.scale();
        String label = ClientText.get(ClientText.Keys.COMMAND_WEAPON_LABEL);
        weaponFull = model.weapon;
        int budget = Math.max(1, row.w() - textWidth(font, label) - Scale.effectivePx(4, scale));
        weaponShown = ellipsis(font, weaponFull, budget);
        weaponTruncated = !weaponShown.equals(weaponFull);
        int lineY = baseline(row, scale);
        text(graphics, font, label, row.x(), lineY, col(Slate.TEXT2, alpha));
        textRight(graphics, font, weaponShown, row.right(), lineY,
                col(model.weaponSynced ? Slate.TEXT : Slate.TEXT3, alpha));
    }

    /**
     * One vitals row: label on the left, value right-aligned on the same line, thin track below.
     *
     * <p>The two-column standard form draws {@code 100/100} - the prototype's wording, because the
     * spaced form does not fit 70 pixels next to the label and the value's ceiling matters more than
     * the spaces. The single-column narrow form keeps {@code 100 / 100}. A value that still does not
     * fit is cut like the weapon line and keeps its whole text in a tooltip.</p>
     */
    private com.tactical.neoforge.client.ui.layout.TextMetrics.Fitted drawGauge(GuiGraphics graphics, Rect value,
                                Rect bar, String label, String spaced, String compact, double fraction,
                                int fillColor, double alpha) {
        Font font = this.font;
        double scale = layout.scale();
        String full = layout.narrow() ? spaced : compact;
        int lineY = baseline(value, scale);
        // The number keeps priority: an English label that does not leave room for the whole value is the
        // part that gets the ellipsis, and the value is only ever cut when it cannot fit on its own.
        int gap = Scale.effectivePx(4, scale);
        int valueWidth = textWidth(font, full);
        int labelWidth = textWidth(font, label);
        int labelBudget = Math.max(1, value.w() - gap - valueWidth);
        String labelShown = labelWidth <= labelBudget ? label
                : com.tactical.neoforge.client.ui.layout.TextMetrics.ellipsis(label, labelBudget, scale, font::width);
        text(graphics, font, labelShown, value.x(), lineY, col(Slate.TEXT2, alpha));
        com.tactical.neoforge.client.ui.layout.TextMetrics.Fitted fitted =
                com.tactical.neoforge.client.ui.layout.TextMetrics.fitAfterLabel(full, value.w(),
                        textWidth(font, labelShown), gap, scale, font::width);
        textRight(graphics, font, fitted.shown(), value.right(), lineY, col(Slate.TEXT, alpha));
        Draw.bar(graphics, bar, col(fillColor, alpha), fraction);
        return fitted;
    }

    private void drawRows(GuiGraphics graphics, long now, double delta, double alpha) {
        boolean narrow = layout.narrow();
        Font font = this.font;
        double scale = layout.scale();
        int rowInset = Scale.effectivePx(narrow ? Motion.ICON_LEFT_NARROW : Motion.ICON_BOX_INSET, scale);
        int rowTop = Scale.effectivePx(narrow ? Motion.ICON_BOX_INSET_NARROW : Motion.ICON_BOX_INSET, scale);
        int textLeft = Scale.effectivePx(narrow ? Motion.ICON_TEXT_LEFT_NARROW : Motion.ICON_TEXT_LEFT, scale);
        int linePad = Scale.effectivePx(Motion.ROW_GAP, scale);
        List<String> actions = layout.visibleActions();
        // Only what this frame really drew may be reported: a row that scrolled out is gone from the
        // probe as well as from the hit order.
        rowText.clear();
        Rect viewport = layout.viewport();
        boolean clipped = layout.rowsScrollable() && viewport.h() > 0;
        Runnable rows = () -> {
            for (int index = 0; index < actions.size(); index++) {
                String action = actions.get(index);
                String id = CommandMenuLayout.rowId(action);
                CommandMenuModel.Row row = model.row(action);
                if (row == null) continue;
                Rect rect = layout.rect(id);
                if (rect.w() <= 0 || rect.h() <= 0) continue;
                double rowAlpha = alpha * Motion.easeOutCubic(Motion.progress(
                        now - openedAt - Math.min(index * Motion.ROW_STAGGER_MS, Motion.ROW_STAGGER_MAX_MS),
                        Motion.PANEL_ENTER_MS));
                boolean hovered = id.equals(hoveredId);
                double progress = tween.progress(id, hovered, now, Motion.HOVER_MS, delta);
                boolean pressed = id.equals(capture.pressed()) && id.equals(hoveredId);
                int rise = narrow || pressed || progress < 0.5D ? 0 : -1;
                Rect box = rect.move(0, pressed ? 1 : rise);
                int base = pressed ? Slate.SUNK : Draw.blend(Slate.RAISE, Slate.HOVER, progress);
                Draw.chip(graphics, box, col(base, rowAlpha));
                if (narrow && hovered) {
                    Draw.fill(graphics, box.x(), box.y(), Scale.effectivePx(2, scale), box.h(), col(UiPalette.accent(), rowAlpha));
                }
                int iconSize = Scale.effectivePx(Motion.ICON, scale);
                Rect icon = Rect.of(box.x() + rowInset, box.y() + rowTop, iconSize, iconSize);
                Draw.fill(graphics, icon, col(hovered || pressed ? UiPalette.accentDark() : Slate.SUNK, rowAlpha));
                drawActionIcon(graphics, action, icon);

                int textX = box.x() + textLeft;
                int textRight = box.right() - Scale.effectivePx(4, scale);
                String cost = row.enabled() && !row.cost().isEmpty() ? row.cost() : "";
                // The row's own name is what the player is choosing, so it keeps its room first; a cost that
                // is prose ("By terrain") is what gets the ellipsis. A numeric cost always fits: the server's
                // own prices are one or two digits plus the unit.
                int nameWidth = textWidth(font, row.name());
                int room = Math.max(1, textRight - textX);
                String costShown = cost;
                if (!cost.isEmpty() && nameWidth + linePad + textWidth(font, cost) > room) {
                    int costBudget = Math.max(1, room - nameWidth - linePad);
                    costShown = com.tactical.neoforge.client.ui.layout.TextMetrics.ellipsis(cost, costBudget, scale,
                            font::width);
                }
                int costWidth = costShown.isEmpty() ? 0 : textWidth(font, costShown) + linePad;
                String name = ellipsis(font, row.name(), Math.max(1, room - costWidth));
                int nameColor = row.enabled() ? Slate.TEXT : Slate.TEXT3;
                int nameY = narrow ? box.y() + (box.h() - Scale.effectivePx(TEXT_LINE, scale)) / 2 : box.y() + Scale.effectivePx(2, scale);
                text(graphics, font, name, textX, nameY, col(nameColor, rowAlpha));
                if (!costShown.isEmpty()) {
                    int costColor = row.enabled() && row.affordable() ? Slate.AP : Slate.TEXT2;
                    textRight(graphics, font, costShown, textRight, nameY, col(costColor, rowAlpha));
                }
                if (!narrow) {
                    String description = ellipsis(font, row.description(), Math.max(1, textRight - textX));
                    rowText.put(action, new String[]{row.description(), description, name});
                    int descriptionColor = !row.enabled() ? Slate.TEXT3 : progress >= 0.5D ? Slate.TEXT : Slate.TEXT2;
                    text(graphics, font, description, textX, box.y() + Scale.effectivePx(11, scale),
                            col(descriptionColor, rowAlpha));
                } else {
                    rowText.remove(action);
                }
            }
        };
        if (clipped) Draw.scissor(graphics, viewport, rows);
        else rows.run();
        drawRowViewportMarks(graphics, alpha);
    }

    /**
     * The scrollbar and the two 1px rules that mark the viewport's edges.
     *
     * <p>They exist only while some row is out of sight: a complete list looks exactly as it always did,
     * with neither a bar nor a border, so nothing about the panel changes just because a window grew.</p>
     */
    private void drawRowViewportMarks(GuiGraphics graphics, double alpha) {
        if (!layout.rowsScrollable()) return;
        Rect top = layout.rect(CommandMenuLayout.VIEWPORT_TOP_RULE);
        Rect bottom = layout.rect(CommandMenuLayout.VIEWPORT_BOTTOM_RULE);
        if (top.h() > 0) Draw.fill(graphics, top, col(Slate.LINE, alpha));
        if (bottom.h() > 0) Draw.fill(graphics, bottom, col(Slate.LINE, alpha));
        Rect track = layout.scrollBar();
        if (track.w() <= 0) return;
        Draw.fill(graphics, track, col(Slate.SUNK, alpha));
        Rect thumb = com.tactical.neoforge.client.ui.layout.ScrollList.thumb(track,
                layout.actions().size(), layout.visibleRows(), layout.firstRow(), layout.scale());
        if (thumb.h() > 0) {
            // The bar brightens exactly like the other two lists': hovering the rows lifts the thumb.
            boolean hot = hoveredId != null && hoveredId.startsWith("row-");
            Draw.fill(graphics, thumb, col(hot ? Slate.TEXT3 : Slate.LINE, alpha));
        }
    }

    private void drawFooter(GuiGraphics graphics, double alpha) {
        Rect rule = layout.rect(CommandMenuLayout.FOOTER_RULE);
        if (rule.w() <= 0) return;
        Draw.separator(graphics, rule);
        Rect footer = layout.rect(CommandMenuLayout.FOOTER);
        footerFull = model.footer;
        String shown = ellipsis(this.font, footerFull, footer.w());
        footerTruncated = !shown.equals(footerFull);
        text(graphics, this.font, shown, footer.x(), baseline(footer, layout.scale()), col(Slate.TEXT3, alpha));
    }

    private void drawPopover(GuiGraphics graphics, long now, double delta, double alpha) {
        Rect popover = layout.rect(CommandMenuLayout.POPOVER);
        if (popover.w() <= 0) return;
        Draw.shadow(graphics, popover, Scale.SMALL_SHADOW, col(Slate.SHADOW_SM, alpha), false);
        // The popover floats above the panel, so its base is the opaque floating surface: the panel's
        // own text must never show through, not even on the first frame of the fade-in. The fade only
        // dims the contents, never the base.
        Draw.chip(graphics, popover, Slate.FLOAT);
        symbolButton(graphics, CommandMenuLayout.POP_MINUS, Draw.ICON_MINUS, now, delta, alpha,
                layout.minusEnabled());
        symbolButton(graphics, CommandMenuLayout.POP_PLUS, Draw.ICON_PLUS, now, delta, alpha,
                layout.plusEnabled());
        Rect value = layout.rect(CommandMenuLayout.POP_VALUE);
        textCentered(graphics, this.font, UiZoom.percent() + "%", value,
                baseline(value, layout.scale()), col(Slate.TEXT, alpha));
        // The popover holds two different resets: this one returns the size preset to 100%, the third of
        // the entries below returns the roster/journal sizes. They must never share a label.
        button(graphics, CommandMenuLayout.POP_RESET, ClientText.get(ClientText.Keys.GEAR_RESET_ZOOM),
                now, delta, alpha, true);
        button(graphics, CommandMenuLayout.POP_LEGEND, popoverLabel(CommandMenuLayout.POP_LEGEND), now, delta, alpha,
                true);
        // The three entries this batch adds, between the range legend and Close: two of them carry the
        // colour they edit as an 8x8 swatch at the left, the third is a plain disabled-able button.
        swatchButton(graphics, CommandMenuLayout.POP_THEME, ColorTextResolver.get(ColorText.gearKey(ColorDraft.Kind.THEME)),
                UiPalette.theme(), now, delta, alpha, true);
        swatchButton(graphics, CommandMenuLayout.POP_RANGE_COLOR,
                ColorTextResolver.get(ColorText.gearKey(ColorDraft.Kind.RANGE)),
                UiPalette.range(), now, delta, alpha, true);
        button(graphics, CommandMenuLayout.POP_RESET_SIZES,
                ColorTextResolver.get(ColorText.GEAR_RESET_SIZES), now, delta, alpha,
                layout.resetSizesEnabled());
        button(graphics, CommandMenuLayout.POP_CLOSE, ClientText.get(ClientText.Keys.GEAR_CLOSE), now, delta, alpha,
                true);
    }

    /**
     * A popover button with the colour it edits as an 8x8 swatch at its left edge: the text starts at
     * 15 from the inner left, so the swatch never touches the glyphs.
     */
    private void swatchButton(GuiGraphics graphics, String id, String label, int rgb, long now, double delta,
                              double alpha, boolean enabled) {
        Rect rect = buttonBase(graphics, id, now, delta, alpha, enabled);
        double scale = layout.scale();
        int size = Scale.effectivePx(8, scale);
        Rect swatch = Rect.of(rect.x() + Scale.effectivePx(3, scale),
                rect.y() + (rect.h() - size) / 2, size, size);
        Draw.chip(graphics, swatch, Palette.accent(rgb));
        buttonLabel(graphics, id, label, rect, now, delta, alpha, enabled, Scale.effectivePx(15, scale));
    }

    private void button(GuiGraphics graphics, String id, String label, long now, double delta,
                        double alpha, boolean enabled) {
        Rect rect = buttonBase(graphics, id, now, delta, alpha, enabled);
        buttonLabel(graphics, id, label, rect, now, delta, alpha, enabled, 0);
    }

    /**
     * A button whose whole face is the user's picture: the size preset's {@code −} and {@code +}.
     *
     * <p>The two states draw exactly the same surface and the same symbol; only the symbol's colour differs
     * (white while the preset can move, grey at its end), so the shape never claims a permission the press does
     * not have. The symbol is placed by {@code Draw.sprite}, i.e. centred on the button's own centre and
     * independent of the language's font.</p>
     */
    private void symbolButton(GuiGraphics graphics, String id, Draw.Sprite symbol, long now, double delta,
                              double alpha, boolean enabled) {
        Rect rect = buttonBase(graphics, id, now, delta, alpha, enabled);
        boolean pressed = enabled && id.equals(capture.pressed()) && id.equals(hoveredId);
        Draw.sprite(graphics, symbol, pressed ? rect.move(0, 1) : rect,
                col(enabled ? Slate.TEXT : Slate.TEXT3, alpha));
    }

    /** The button's own surface: RAISE, hovered towards HOVER, SUNK while pressed and when disabled. */
    private Rect buttonBase(GuiGraphics graphics, String id, long now, double delta, double alpha,
                            boolean enabled) {
        Rect rect = layout.rect(id);
        boolean hovered = enabled && id.equals(hoveredId);
        double progress = tween.progress(id, hovered, now, Motion.HOVER_MS, delta);
        boolean pressed = enabled && id.equals(capture.pressed()) && id.equals(hoveredId);
        // Disabled: SUNK base with tertiary text, no hover and no press, per the visual specification.
        int base = !enabled ? Slate.SUNK : pressed ? Slate.SUNK : Draw.blend(Slate.RAISE, Slate.HOVER, progress);
        Draw.chip(graphics, rect, col(base, alpha));
        return rect;
    }

    /**
     * The button's label, centred unless a leading inset reserves room for an icon or a swatch.
     *
     * @param leftInset how much of the left edge belongs to something else; 0 means "just centred"
     */
    private void buttonLabel(GuiGraphics graphics, String id, String label, Rect rect, long now, double delta,
                             double alpha, boolean enabled, int leftInset) {
        boolean hovered = enabled && id.equals(hoveredId);
        boolean pressed = enabled && id.equals(capture.pressed()) && hovered;
        int pad = Scale.effectivePx(Motion.BUTTON_PAD_X, layout.scale());
        int left = rect.x() + Math.max(pad, leftInset);
        int budget = Math.max(1, rect.right() - pad - left);
        // A one-glyph icon button ("-", "+") is centred as it is: folding it would turn a sign into
        // an ellipsis, which is what a too-narrow 18px button used to do to the full-width minus.
        String text = com.tactical.neoforge.client.ui.layout.TextMetrics.iconOrEllipsis(label, budget,
                layout.scale(), this.font::width);
        int textWidth = textWidth(this.font, text);
        int x = leftInset > 0 ? left : rect.x() + (rect.w() - textWidth) / 2;
        text(graphics, this.font, text, x, baseline(rect, layout.scale()) + (pressed ? 1 : 0),
                col(enabled ? Slate.TEXT : Slate.TEXT3, alpha));
    }

    /**
     * Read-only areas that show a tooltip but never take a click: the weapon line and a vitals value
     * that had to be cut.
     */
    private String passiveHover(double pointerX, double pointerY) {
        Rect popover = layout.rect(CommandMenuLayout.POPOVER);
        if (popover.w() > 0 && popover.contains(pointerX, pointerY)) return null;
        if (titleTruncated && layout.rect(CommandMenuLayout.TITLE).contains(pointerX, pointerY)) {
            return CommandMenuLayout.TITLE;
        }
        if (footerTruncated && layout.rect(CommandMenuLayout.FOOTER).contains(pointerX, pointerY)) {
            return CommandMenuLayout.FOOTER;
        }
        Rect weapon = layout.rect(CommandMenuLayout.WEAPON);
        if (weapon.contains(pointerX, pointerY)) return CommandMenuLayout.WEAPON;
        if (hpText.truncated() && layout.rect(CommandMenuLayout.HP_VALUE).contains(pointerX, pointerY)) {
            return CommandMenuLayout.HP_VALUE;
        }
        if (mpText.truncated() && layout.rect(CommandMenuLayout.MP_VALUE).contains(pointerX, pointerY)) {
            return CommandMenuLayout.MP_VALUE;
        }
        return null;
    }

    /**
     * Tooltip of the hovered control: {@code FLOAT} surface (opaque, so it may cover the panel) with
     * the small corner and small shadow, padding 3/4, folded to the maximum width, first line
     * {@code TEXT} and supplementary lines {@code TEXT2}, appearing 350ms after the pointer stops and
     * fading in over 90ms. It is placed beside its control and never under the pointer.
     */
    /**
     * The wording a gear-popover button draws. Drawing and tooltip read it from here, so the whole label
     * of a button that had to be shortened - the range-legend switch says which state it is in - is
     * readable without a second copy of the text or a shorter English label.
     */
    private String popoverLabel(String id) {
        if (CommandMenuLayout.POP_LEGEND.equals(id)) {
            return ClientText.get(UiZoom.showRangeLegend()
                    ? ClientText.Keys.GEAR_LEGEND_ON : ClientText.Keys.GEAR_LEGEND_OFF);
        }
        return "";
    }

    private void drawTooltip(GuiGraphics graphics, long now) {
        tooltipBox = com.tactical.neoforge.client.ui.layout.Rect.EMPTY;
        tooltipLines = java.util.List.of();
        if (hoveredId == null || !tween.tooltipDue(now)) return;
        // The colour layer is painted above the gear popover and takes the pointer: its own panel must not
        // have an old popover button's tooltip showing underneath it.
        if (colors.active()) return;
        String primary;
        String secondary = null;
        Rect trigger;
        String popoverFull = popoverLabel(hoveredId);
        if (!popoverFull.isEmpty()) {
            primary = popoverFull;
            trigger = layout.rect(hoveredId);
        } else if (CommandMenuLayout.TITLE.equals(hoveredId)) {
            if (!titleTruncated) return;
            primary = titleFull;
            trigger = layout.rect(CommandMenuLayout.TITLE);
        } else if (CommandMenuLayout.FOOTER.equals(hoveredId)) {
            if (!footerTruncated) return;
            primary = footerFull;
            trigger = layout.rect(CommandMenuLayout.FOOTER);
        } else if (CommandMenuLayout.WEAPON.equals(hoveredId)) {
            // Only a cut value needs the tooltip: otherwise it would merely repeat the line.
            if (!weaponTruncated) return;
            primary = weaponFull;
            trigger = layout.rect(CommandMenuLayout.WEAPON);
        } else if (CommandMenuLayout.HP_VALUE.equals(hoveredId) || CommandMenuLayout.MP_VALUE.equals(hoveredId)) {
            // Same rule for a vitals value that no longer fits its column.
            var gauge = CommandMenuLayout.HP_VALUE.equals(hoveredId) ? hpText : mpText;
            if (!gauge.truncated()) return;
            primary = gauge.full();
            trigger = layout.rect(hoveredId);
        } else {
            CommandMenuModel.Row hoveredRow = null;
            for (CommandMenuModel.Row row : model.rows) {
                if (CommandMenuLayout.rowId(row.action()).equals(hoveredId)) { hoveredRow = row; break; }
            }
            if (hoveredRow == null) return;
            primary = hoveredRow.description();
            // The narrow rows hide the description, and the attack row also carries the weapon line
            // that the narrow form does not show. The standard form's attack row must not repeat it.
            if (layout.narrow() && "attack".equals(hoveredRow.action())) secondary = model.weapon;
            trigger = layout.rect(hoveredId);
        }
        if (primary == null || primary.isEmpty() || trigger.w() <= 0) return;
        double scale = layout.scale();
        com.tactical.neoforge.client.ui.widget.Tooltip.Content content =
                com.tactical.neoforge.client.ui.widget.Tooltip.content(primary, secondary, scale, this.font::width);
        if (content.isEmpty()) return;
        int boxWidth = com.tactical.neoforge.client.ui.widget.Tooltip.width(content, scale, this.font::width);
        int boxHeight = com.tactical.neoforge.client.ui.widget.Tooltip.height(content, scale);
        Rect box = com.tactical.neoforge.client.ui.widget.Tooltip.place(trigger, boxWidth, boxHeight, width, height,
                mouseX, mouseY, Scale.effectivePx(com.tactical.neoforge.client.ui.widget.Tooltip.GAP, scale));
        double fade = Motion.easeOutCubic(Motion.progress(now - tween.hoveredSince() - Motion.TOOLTIP_DELAY_MS,
                Motion.TOOLTIP_FADE_MS));
        Draw.shadow(graphics, box, Scale.SMALL_SHADOW, Motion.withAlpha(Slate.SHADOW_SM, fade), false);
        // A tooltip is a floating surface: its base is opaque from the first frame of the fade, so the
        // panel under it never shows through the text it is covering.
        Draw.chip(graphics, box, Slate.FLOAT);
        int textLeft = com.tactical.neoforge.client.ui.widget.Tooltip.textLeft(box, scale);
        int lineY = com.tactical.neoforge.client.ui.widget.Tooltip.textTop(box, scale);
        int lineHeight = Scale.effectivePx(TEXT_LINE, scale);
        java.util.List<String> drawn = new java.util.ArrayList<>();
        for (String line : content.primary()) {
            text(graphics, this.font, line, textLeft, lineY, Motion.withAlpha(Slate.TEXT, fade));
            drawn.add(line);
            lineY += lineHeight;
        }
        for (String line : content.secondary()) {
            text(graphics, this.font, line, textLeft, lineY, Motion.withAlpha(Slate.TEXT2, fade));
            drawn.add(line);
            lineY += lineHeight;
        }
        tooltipBox = box;
        tooltipLines = java.util.List.copyOf(drawn);
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double pointerX, double pointerY, int button) {
        ensureLayout();
        // The layer order the approved routing asks for: the open settings popover, then the pointer
        // capture of a running drag, then the HUD size handles, then the page. A popover therefore
        // takes a click that lands on it before a handle underneath it would, and a click outside the
        // colour popover closes it without applying anything.
        if (colors.active()) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
            if (colors.contains(pointerX, pointerY)) {
                return colors.mousePressed(pointerX, pointerY, button, this.font, layout.scale());
            }
            colors.close();
            return true;
        }
        Rect popover = layout.rect(CommandMenuLayout.POPOVER);
        boolean inPopover = popover.w() > 0 && popover.contains(pointerX, pointerY);
        if (!popups.isEmpty() && inPopover) {
            if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
            capture.cancel();
            Box inside = HitTest.topmost(layout.hitOrder(), pointerX, pointerY);
            // A click inside the popover belongs to the popover even when it lands on a disabled
            // control; only a click outside its rectangle dismisses it.
            if (inside == null) return true;
            capture.press(inside.id());
            tween.pressed(inside.id(), Util.getMillis());
            return true;
        }
        // The interactive HUD gets the next look, so the journal toggle and the roster keep working
        // while the command panel is open. Nothing else of the HUD can reach the panel.
        if (super.mouseClicked(pointerX, pointerY, button)) return true;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        capture.cancel();
        Box hit = HitTest.topmost(layout.hitOrder(), pointerX, pointerY);
        if (!popups.isEmpty() && !inPopover) {
            // The popover owns the layer above the page: a click outside it closes it, nothing else.
            popups.clear();
            rebuild();
            return true;
        }
        if (hit == null) return true;
        capture.press(hit.id());
        tween.pressed(hit.id(), Util.getMillis());
        return true;
    }

    @Override
    public boolean mouseDragged(double pointerX, double pointerY, int button, double dragX, double dragY) {
        if (colors.active() && colors.mouseDragged(pointerX, pointerY)) return true;
        return super.mouseDragged(pointerX, pointerY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double pointerX, double pointerY, int button) {
        if (colors.active() && colors.mouseReleased(pointerX, pointerY, button)) {
            ensureLayout();
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        ensureLayout();
        if (minecraft != null && !minecraft.isWindowActive()) capture.cancel();
        Box hit = HitTest.topmost(layout.hitOrder(), pointerX, pointerY);
        String id = capture.release(hit == null ? null : hit.id());
        if (id != null) activate(id);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // The colour popover is modal for the keyboard while it is open: Escape closes it and drops
        // the draft, and a focused HEX field never lets a hotbar key or a battle shortcut through.
        if (colors.active()) {
            if (colors.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (colors.hexFocused()) return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) capture.cancel();
        if (BattleScreenInput.handleKey(this, keyCode, scanCode)) return true;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (popups.escape()) {
                rebuild();
                return true;
            }
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (colors.charTyped(codePoint)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    /**
     * The action viewport's wheel.
     *
     * <p>Only a notch over the viewport belongs to the list. It is always consumed there - at the top or
     * the bottom edge as well - so a notch aimed at the rows never reaches the hotbar or the camera. The
     * direction is the raw delta, the same convention the roster and the journal use, and one notch is
     * exactly one row.</p>
     */
    @Override
    public boolean scrollAt(double guiX, double guiY, double delta) {
        ensureLayout();
        if (layout == null || !layout.rowsScrollable()) return false;
        if (!layout.viewport().contains(guiX, guiY)) return false;
        int next = layout.scrollBy(scroll, delta);
        if (next == scroll) return true;
        scroll = next;
        // A press made before the scroll must never become a click on the row that slides under the
        // pointer: the whole gesture is abandoned instead of being completed by the next release.
        capture.cancel();
        rebuild();
        return true;
    }

    /** Runs one control of this page; the page's own method, not an override of the native base. */
    protected void activate(String id) {
        switch (id) {
            case CommandMenuLayout.GEAR -> {
                popups.toggle(SETTINGS);
                rebuild();
            }
            case CommandMenuLayout.POP_MINUS -> {
                UiZoom.scale(-1);
                rebuild();
            }
            case CommandMenuLayout.POP_PLUS -> {
                UiZoom.scale(1);
                rebuild();
            }
            case CommandMenuLayout.POP_RESET -> {
                UiZoom.reset();
                rebuild();
            }
            case CommandMenuLayout.POP_LEGEND -> UiZoom.toggleRangeLegend();
            case CommandMenuLayout.POP_THEME -> openColors(ColorDraft.Kind.THEME);
            case CommandMenuLayout.POP_RANGE_COLOR -> openColors(ColorDraft.Kind.RANGE);
            case CommandMenuLayout.POP_RESET_SIZES -> {
                // Only geometry: the preset, the colours and the journal's open state are untouched.
                // The HUD rebuilds its layout every frame from the preferences, so nothing to notify.
                UiZoom.resetHudSizes();
                rebuild();
            }
            case CommandMenuLayout.POP_CLOSE -> BattleMenu.dismiss();
            default -> {
                for (String action : layout.actions()) {
                    if (CommandMenuLayout.rowId(action).equals(id)) {
                        popups.clear();
                        BattleMenu.activate(action);
                        return;
                    }
                }
            }
        }
    }

    /**
     * The action rows this frame draws.
     *
     * <p>A companion turn draws its five orders and nothing else; a player turn keeps the nine commands it
     * always had. The list is what the layout, the hit tests, the viewport and the wheel all follow, so no
     * second geometry path exists for companions.</p>
     */
    private static java.util.List<String> actionsForFrame() {
        return BattleClientState.isCommandingCompanion()
                ? CommandMenuLayout.COMPANION_ACTIONS
                : CommandMenuLayout.ACTIONS;
    }

    /** Opens the colour popover beside the gear popover; the draft starts from what is applied. */
    private void openColors(ColorDraft.Kind kind) {
        colors.open(kind);
        colors.ensure(width, height, layout.scale(), layout.narrow(),
                layout.rect(CommandMenuLayout.POPOVER), this.font);
    }

    @Override
    public void onClose() {
        capture.cancel();
        // Closing the page is a Cancel for an open colour draft: nothing is applied, nothing written.
        colors.close();
        BattleMenu.dismiss();
    }

    @Override
    public void removed() {
        capture.cancel();
        colors.close();
        super.removed();
    }

    /**
     * The colour popover is painted here, after the HUD's own frame, because the HUD renders from
     * {@code ScreenEvent.Render.Post} - anywhere in the screen's content would be under the roster and
     * the charge rail. Geometry and hover are already up to date from {@code drawContent}.
     */
    @Override
    public void renderTopOverlay(GuiGraphics graphics, int pointerX, int pointerY, float partialTick) {
        ensureLayout();
        boolean contentDrawsIt = TooltipLayer.forFrame(hudHidden()) == TooltipLayer.CONTENT;
        if (!colors.active()) {
            // No modal layer: this pass owes the ordinary tooltip, above the HUD's own depth.
            if (contentDrawsIt) return;
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, com.tactical.neoforge.client.ui.screen.TacticalSurface.TOP_OVERLAY_DEPTH);
            try {
                drawTooltip(graphics, Util.getMillis());
            } finally {
                graphics.pose().popPose();
            }
            return;
        }
        colors.ensure(width, height, layout.scale(), layout.narrow(),
                layout.rect(CommandMenuLayout.POPOVER), this.font);
        colors.hover(pointerX, pointerY);
        graphics.pose().pushPose();
        // Above everything the HUD writes depth for. The charge rail's portraits are real entity
        // models: Portraits.entity pushes +400 and the vanilla renderer it calls
        // (InventoryScreen.renderEntityInInventory) pushes another +50 before flushing the model, so
        // the deepest HUD geometry sits at 450. A popover at +50 was therefore *behind* the model and
        // the legs showed through its title; 600 is above the deepest layer, not merely above the HUD's
        // plain fills.
        graphics.pose().translate(0, 0, com.tactical.neoforge.client.ui.screen.TacticalSurface.TOP_OVERLAY_DEPTH);
        try {
            colors.draw(graphics, this.font, layout.scale(), Util.getMillis());
        } finally {
            graphics.pose().popPose();
        }
    }

    /** True while the player has the whole HUD hidden, which also stops the top-overlay hook. */
    private static boolean hudHidden() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.options != null && minecraft.options.hideGui;
    }

    @Override
    public boolean consumesWheel() {
        return colors.active();
    }

    @Override
    public boolean overUi(double guiX, double guiY) {
        ensureLayout();
        if (colors.active() && colors.contains(guiX, guiY)) return true;
        if (layout.panel().contains(guiX, guiY)) return true;
        Rect popover = layout.rect(CommandMenuLayout.POPOVER);
        return popover.w() > 0 && popover.contains(guiX, guiY);
    }

    @Override
    public boolean consumesClick(double guiX, double guiY) {
        ensureLayout();
        if (colors.active() && colors.contains(guiX, guiY)) return true;
        return HitTest.topmost(layout.hitOrder(), guiX, guiY) != null;
    }

    // ---------------------------------------------------------------- probe

    @Override
    protected String probeJson() {
        ensureLayout();
        StringBuilder nodes = new StringBuilder();
        Map<String, Rect> rects = layout.allRects();
        boolean first = true;
        for (Map.Entry<String, Rect> entry : rects.entrySet()) {
            Rect rect = entry.getValue();
            if (rect.w() <= 0 || rect.h() <= 0) continue;
            if (!first) nodes.append(',');
            first = false;
            nodes.append("{\"id\":\"").append(entry.getKey()).append("\",\"x\":").append(rect.x())
                    .append(",\"y\":").append(rect.y()).append(",\"w\":").append(rect.w())
                    .append(",\"h\":").append(rect.h()).append(",\"hovered\":")
                    .append(entry.getKey().equals(hoveredId));
            if (entry.getKey().startsWith("row-")) {
                CommandMenuModel.Row row = model == null ? null : model.row(entry.getKey().substring(4));
                nodes.append(",\"enabled\":").append(row == null || row.enabled());
            }
            nodes.append('}');
        }
        Rect panel = layout.panel();
        Rect weapon = layout.rect(CommandMenuLayout.WEAPON);
        // The entrance alpha and the exact colours handed to the font: a reviewer can tell a faded
        // frame from a dim glyph without guessing from a screenshot.
        int textArgb = Motion.withAlpha(Slate.TEXT, lastAlpha);
        int text2Argb = Motion.withAlpha(Slate.TEXT2, lastAlpha);
        String tooltip = tooltipBox.w() <= 0 ? "null" : String.format(Locale.ROOT,
                "{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d,\"padX\":%d,\"padY\":%d,\"lines\":%s}",
                tooltipBox.x(), tooltipBox.y(), tooltipBox.w(), tooltipBox.h(),
                Scale.effectivePx(com.tactical.neoforge.client.ui.widget.Tooltip.PAD_X, layout.scale()),
                Scale.effectivePx(com.tactical.neoforge.client.ui.widget.Tooltip.PAD_Y, layout.scale()),
                tooltipLinesJson());
        return String.format(Locale.ROOT,
                "{\"gui\":{\"w\":%d,\"h\":%d,\"scale\":%.3f,\"preset\":%d,\"form\":\"%s\",\"narrow\":%b,"
                        + "\"tight\":%b,\"cramped\":%b,\"textFloor\":%.3f},"
                        + "\"panel\":{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d},"
                        + "\"weapon\":{\"h\":%d,\"text\":\"%s\",\"shown\":\"%s\",\"synced\":%b,\"truncated\":%b},"
                        + "\"vitals\":{\"hp\":%s,\"mp\":%s},"
                        + "\"popoverButtons\":{\"minus\":\"%s\",\"plus\":\"%s\",\"minusEnabled\":%b,\"plusEnabled\":%b,"
                        + "\"theme\":\"%s\",\"range\":\"%s\","
                        + "\"resetZoom\":\"%s\",\"resetSizes\":\"%s\",\"resetSizesEnabled\":%b},"
                        + "\"color\":%s,"
                        + "\"rows\":%s,"
                        + "\"alpha\":{\"enter\":%.3f,\"text\":\"%08X\",\"text2\":\"%08X\"},"
                        + "\"tooltip\":%s,\"rowViewport\":%s,\"nodes\":[%s]}",
                width, height, layout.scale(), UiZoom.percent(), layout.form(), layout.narrow(),
                layout.tight(), layout.cramped(), textFloor(),
                panel.x(), panel.y(), panel.w(), panel.h(),
                weapon.h(), escape(weaponFull), escape(weaponShown),
                model != null && model.weaponSynced, weaponTruncated,
                gaugeJson(hpText), gaugeJson(mpText), POP_MINUS_LABEL, POP_PLUS_LABEL,
                layout.minusEnabled(), layout.plusEnabled(),
                escape(ColorTextResolver.get(ColorText.GEAR_THEME)),
                escape(ColorTextResolver.get(ColorText.GEAR_RANGE)),
                escape(ClientText.get(ClientText.Keys.GEAR_RESET_ZOOM)),
                escape(ColorTextResolver.get(ColorText.GEAR_RESET_SIZES)),
                layout.resetSizesEnabled(), colors.probeJson(),
                rowsJson(),
                lastAlpha, textArgb, text2Argb, tooltip, rowViewportJson(), nodes);
    }

    /** The action viewport and its scroll state, so a driver can aim at the right rows. */
    private String rowViewportJson() {
        Rect viewport = layout.viewport();
        return String.format(Locale.ROOT,
                "{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d,\"scrollable\":%b,\"offset\":%d,\"visible\":%d,"
                        + "\"total\":%d,\"maxOffset\":%d,\"first\":\"%s\",\"last\":\"%s\"}",
                viewport.x(), viewport.y(), viewport.w(), viewport.h(), layout.rowsScrollable(),
                layout.firstRow(), layout.visibleRows(), layout.actions().size(),
                layout.maxFirstRow(),
                layout.visibleActions().isEmpty() ? "" : layout.visibleActions().get(0),
                layout.visibleActions().isEmpty() ? ""
                        : layout.visibleActions().get(layout.visibleActions().size() - 1));
    }

    /** One drawn action row for the probe: the whole description, what was drawn and its name. */
    private String rowsJson() {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (String action : layout.actions()) {
            String[] recorded = rowText.get(action);
            if (recorded == null) continue;
            if (!first) builder.append(',');
            first = false;
            builder.append("{\"id\":\"").append(CommandMenuLayout.rowId(action))
                    .append("\",\"name\":\"").append(escape(recorded[2]))
                    .append("\",\"description\":\"").append(escape(recorded[0]))
                    .append("\",\"shown\":\"").append(escape(recorded[1]))
                    .append("\",\"truncated\":").append(!recorded[1].equals(recorded[0])).append('}');
        }
        return builder.append(']').toString();
    }

    /** One vitals value for the probe: the whole text, the drawn text and whether it was cut. */
    private static String gaugeJson(com.tactical.neoforge.client.ui.layout.TextMetrics.Fitted gauge) {
        return String.format(Locale.ROOT, "{\"text\":\"%s\",\"shown\":\"%s\",\"truncated\":%b}",
                escape(gauge.full()), escape(gauge.shown()), gauge.truncated());
    }

    private String tooltipLinesJson() {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < tooltipLines.size(); index++) {
            if (index > 0) builder.append(',');
            builder.append('"').append(escape(tooltipLines.get(index))).append('"');
        }
        return builder.append(']').toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The row's picture, taken from the model's fixed table.
     *
     * <p>The main hand no longer picks a command icon: the eight rows look the same whatever is held,
     * so a row cannot promise an action the command would not take. The id is resolved through the item
     * registry, so the picture is the same item the released panel drew.</p>
     */
    private void drawActionIcon(GuiGraphics graphics, String action, Rect icon) {
        String id = CommandMenuModel.rowIconId(action);
        if (id.isEmpty()) return;
        net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.parse(id));
        net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
        if (stack.isEmpty()) return;
        graphics.pose().pushPose();
        graphics.pose().translate(icon.x(), icon.y(), 0);
        float factor = icon.w() / 16.0F;
        graphics.pose().scale(factor, factor, 1.0F);
        graphics.renderItem(stack, 0, 0);
        graphics.pose().popPose();
    }

    private int textWidth(Font font, String text) {
        return com.tactical.neoforge.client.ui.layout.TextMetrics.width(font.width(text), layout.scale());
    }

    /**
     * The width one identity chip occupies: its text, its two inner paddings and the chip pad.
     *
     * <p>It is measured, never guessed, because the chip's full wording is what the narrow form has to fit or
     * move; the same number decides the space the actor name may keep.</p>
     */
    private int chipWidth(Font font, String label, double scale) {
        if (label == null || label.isEmpty()) return 0;
        return com.tactical.neoforge.client.ui.layout.TextMetrics.width(font.width(label), scale)
                + Scale.effectivePx(2, scale) * 2;
    }

    /** One identity chip: the existing chip shape, never shortened and never renamed. */
    private void drawIdentityChip(GuiGraphics graphics, Font font, double scale, double alpha, int x, int y) {
        String label = model.identityChip;
        if (label == null || label.isEmpty()) return;
        int width = chipWidth(font, label, scale);
        int height = Scale.effectivePx(9, scale);
        Rect box = Rect.of(x, y, width, height);
        Draw.chip(graphics, box, col(Slate.RAISE, alpha));
        int textY = y + (height - Math.max(1, Scale.effectivePx(Motion.LINE, scale))) / 2;
        text(graphics, font, label, x + Scale.effectivePx(2, scale), textY, col(Slate.TEXT2, alpha));
    }

    private String ellipsis(Font font, String text, int width) {
        return com.tactical.neoforge.client.ui.layout.TextMetrics.ellipsis(text, width, layout.scale(), font::width);
    }

    private void text(GuiGraphics graphics, Font font, String value, int x, int y, int color) {
        Draw.scaledText(graphics, font, value, x, y, layout.scale(), color);
    }

    private void textRight(GuiGraphics graphics, Font font, String value, int right, int y, int color) {
        text(graphics, font, value, right - textWidth(font, value), y, color);
    }

    private void textCentered(GuiGraphics graphics, Font font, String value, Rect area, int y, int color) {
        text(graphics, font, value, area.x() + (area.w() - textWidth(font, value)) / 2, y, color);
    }

    private int col(int color, double alpha) {
        return Motion.withAlpha(color, alpha);
    }

    /** Baseline that vertically centres a 1x or 2x line of the given rendered scale in the box. */
    private static int baseline(Rect box, double textScale) {
        int line = (int) Math.round(TEXT_LINE * textScale);
        return box.y() + Math.max(0, (box.h() - line) / 2);
    }
}
