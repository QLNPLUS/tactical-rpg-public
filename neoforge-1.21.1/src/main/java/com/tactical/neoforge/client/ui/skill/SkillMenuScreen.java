package com.tactical.neoforge.client.ui.skill;

import com.tactical.neoforge.client.UiPalette;
import com.tactical.neoforge.client.BattleClientState;
import com.tactical.neoforge.client.SkillClientState;
import com.tactical.neoforge.client.SkillMenu;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.ScrollList;
import com.tactical.neoforge.client.ui.layout.TooltipText;
import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.neoforge.client.ui.panel.NativePanelScreen;
import com.tactical.neoforge.client.ui.panel.PanelLayout;
import com.tactical.neoforge.client.ui.platform.Draw;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Slate;
import com.tactical.neoforge.network.SkillView;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The skill list: every owned skill stays visible while it is unavailable, with the server's reason.
 *
 * <p>Rows are the shared action row extended to four lines. A row that cannot be used is drawn
 * disabled and its click does nothing at all; a usable one selects that skill and hands the page over
 * to the aiming panel, which is where the target is confirmed. The list clips whole rows only and
 * scrolls one row per wheel notch.</p>
 */
public final class SkillMenuScreen extends NativePanelScreen {

    public static final String SCREEN_ID = "battle_skills";
    public static final String SUBTITLE = "subtitle";
    public static final String MP = "mp";
    public static final String MP_BAR = "mp-bar";
    public static final String MP_RECOVERY = "mp-recovery";
    public static final String STATUS = "status";
    public static final String LIST = "skill-list";
    public static final String BACK = "skill-back";
    /** The commanded companion's identity row and its empty state; both exist only on a companion turn. */
    public static final String ACTOR = "companion-actor";
    public static final String EMPTY = "companion-empty";

    /** Standard rows carry name, description, cost and target; the narrow form drops the description. */
    private static final double ROW_HEIGHT = 42;
    private static final double ROW_HEIGHT_NARROW = 33;

    private int scroll;
    private int rows;
    /** The page's own wording as it was measured this frame, so a shortened line can be read whole. */
    private String subtitleFull = "";
    private boolean subtitleTruncated;
    private String statusFull = "";
    private boolean statusTruncated;
    private final List<Rect> rowRects = new ArrayList<>();
    private final List<ResourceLocation> rowIds = new ArrayList<>();
    /**
     * Every field of the rows drawn this frame, whole, so a shortened line can be read in full. Keyed by
     * the row's data index: a row that left the window cannot answer for the row that took its place.
     */
    private final Map<Integer, RowText> rowText = new HashMap<>();

    /** One row's whole wording: what its four lines would say if they had the room. */
    private record RowText(String name, String description, String price, String cooldown, String aim,
                           boolean shortened) {
    }

    @Override
    public String screenId() {
        return SCREEN_ID;
    }

    /**
     * The list's geometry follows the skills themselves, not the frame: a new grant, a lost grant, a
     * replaced loadout of the same length or a change of readiness all rebuild it, while a changing MP
     * number redraws on its own. Without this the page kept the zero-height list it built while the
     * first server snapshot was still empty.
     */
    @Override
    protected long contentFingerprint() {
        return SkillListFingerprint.of(SkillClientState.skills());
    }

    /** True while this page is the static empty companion page instead of the owner's skill list. */
    private static boolean companionPage() {
        return BattleClientState.isCommandingCompanion();
    }

    @Override
    protected PanelLayout build(int guiWidth, int guiHeight, double scale, boolean narrow) {
        if (companionPage()) return buildCompanionPage(guiWidth, guiHeight, scale, narrow);
        PanelLayout layout = PanelLayout.begin(guiWidth, guiHeight, scale, narrow);
        layout.title();
        layout.rule();
        layout.block(SUBTITLE, Motion.LINE);
        layout.gap(Motion.ROW_GAP);
        layout.block(MP, Motion.LINE);
        layout.gap(Motion.STEP + 1);
        layout.block(MP_BAR, Motion.BAR_HEIGHT);
        layout.gap(Motion.ROW_GAP);
        layout.block(MP_RECOVERY, Motion.LINE);
        layout.gap(Motion.GROUP_GAP_ABOVE);
        layout.block(STATUS, Motion.LINE);
        layout.gap(Motion.ROW_GAP);

        // The list takes the space left above the footer; only whole rows are ever drawn.
        int rowHeight = layout.px(narrow ? ROW_HEIGHT_NARROW : ROW_HEIGHT);
        int gap = layout.px(Motion.ROW_GAP);
        int reserve = layout.px(Motion.GROUP_GAP_ABOVE) + Scale.rule(layout.scale())
                + layout.px(Motion.GROUP_GAP_BELOW) + layout.px(Motion.BUTTON_HEIGHT) + layout.px(Motion.PAD);
        int budget = Math.max(0, layout.budgetToBottom() - reserve);
        int total = SkillClientState.skills().size();
        int fits = ScrollList.rows(budget, rowHeight, gap);
        int visible = Math.min(total, fits);
        scroll = ScrollList.clamp(scroll, total, Math.max(1, visible));
        rows = visible;
        int height = ScrollList.height(visible, rowHeight, gap);
        layout.block(LIST, 0);
        Rect list = Rect.of(layout.innerX(), layout.cursor(), layout.innerWidth(), height);
        layout.display(LIST, list);
        layout.moveTo(list.bottom());
        int index = 0;
        rowRects.clear();
        rowIds.clear();
        for (int slot = 0; slot < visible; slot++) {
            int dataIndex = scroll + slot;
            List<SkillView> views = SkillClientState.skills();
            if (dataIndex >= views.size()) break;
            SkillView view = views.get(dataIndex);
            Rect rect = Rect.of(list.x(), list.y() + slot * (rowHeight + gap), list.w(), rowHeight);
            String id = rowId(dataIndex);
            if (view.ready()) layout.interactive(id, rect);
            else layout.disabled(id, rect);
            rowRects.add(rect);
            rowIds.add(view.id());
            index++;
        }
        int backWidth = layout.px(buttonDesignWidth(ClientText.get(ClientText.Keys.BACK_TO_COMMANDS)));
        layout.footer(List.of(BACK), List.of(backWidth), Motion.BUTTON_HEIGHT);
        return layout.finish();
    }

    public static String rowId(int index) {
        return "skill-row-" + index;
    }

    /**
     * The companion page: title, the commanded actor's identity row where the subtitle used to be, one static
     * empty line, and the same back button.
     *
     * <p>Nothing of the owner's is drawn here - no MP row, no MP bar, no recovery line, no skill list, no
     * syncing state - because none of it describes the companion, and no owner skill data is requested for
     * this page.</p>
     */
    private PanelLayout buildCompanionPage(int guiWidth, int guiHeight, double scale, boolean narrow) {
        PanelLayout layout = PanelLayout.begin(guiWidth, guiHeight, scale, narrow);
        layout.title();
        layout.rule();
        layout.block(ACTOR, Motion.LINE);
        layout.gap(Motion.STEP);
        layout.block(EMPTY, Motion.LINE);
        int backWidth = layout.px(buttonDesignWidth(ClientText.get(ClientText.Keys.BACK_TO_COMMANDS)));
        layout.footer(List.of(BACK), List.of(backWidth), Motion.BUTTON_HEIGHT);
        return layout.finish();
    }

    private void drawCompanionBody(GuiGraphics graphics, Font font, double scale, double alpha, long now,
                                   double delta) {
        drawTitle(graphics, font, scale, ClientText.get(ClientText.Keys.SKILL_TITLE), alpha);
        drawRule(graphics);
        Rect actor = layout.rect(ACTOR);
        if (actor.w() > 0) {
            int y = baseline(actor, scale);
            String label = ClientText.get(ClientText.Keys.COMMAND_ACTOR_LABEL);
            Draw.scaledText(graphics, font, label, actor.x(), y, scale, alpha(Slate.TEXT2, alpha));
            String chip = ClientText.get(ClientText.Keys.COMMAND_CHIP_COMPANION);
            int chipWidth = textWidth(font, chip) + Scale.effectivePx(2, scale) * 2;
            int chipHeight = Scale.effectivePx(9, scale);
            int chipX = actor.right() - chipWidth;
            int nameX = actor.x() + textWidth(font, label) + Scale.effectivePx(4, scale);
            String name = BattleClientState.command.name() == null ? "" : BattleClientState.command.name().getString();
            Draw.scaledText(graphics, font,
                    ellipsis(font, name, Math.max(1, chipX - Scale.effectivePx(4, scale) - nameX)), nameX, y,
                    scale, alpha(Slate.TEXT, alpha));
            Rect chipBox = Rect.of(chipX, actor.y() + (actor.h() - chipHeight) / 2, chipWidth, chipHeight);
            Draw.chip(graphics, chipBox, alpha(Slate.RAISE, alpha));
            Draw.scaledText(graphics, font, chip, chipX + Scale.effectivePx(2, scale),
                    chipBox.y() + (chipHeight - Math.max(1, Scale.effectivePx(Motion.LINE, scale))) / 2, scale,
                    alpha(Slate.TEXT2, alpha));
        }
        Rect empty = layout.rect(EMPTY);
        if (empty.w() > 0) {
            String line = ClientText.get(ClientText.Keys.SKILL_COMPANION_EMPTY);
            Draw.scaledText(graphics, font, ellipsis(font, line, empty.w()), empty.x(), baseline(empty, scale),
                    scale, alpha(Slate.TEXT3, alpha));
        }
        button(graphics, font, scale, BACK, ClientText.get(ClientText.Keys.BACK_TO_COMMANDS), true, alpha, now,
                delta);
    }

    @Override
    protected void drawBody(GuiGraphics graphics, Font font, double scale, double alpha, long now, double delta) {
        if (companionPage()) {
            drawCompanionBody(graphics, font, scale, alpha, now, delta);
            return;
        }
        drawTitle(graphics, font, scale, ClientText.get(ClientText.Keys.SKILL_TITLE), alpha);
        drawRule(graphics);
        Rect subtitle = layout.rect(SUBTITLE);
        // The subtitle is one line by the specification: it is measured and shortened with an ellipsis,
        // and its whole wording hangs on the subtitle's tooltip.
        subtitleFull = ClientText.get(ClientText.Keys.SKILL_SUBTITLE);
        String subtitleShown = ellipsis(font, subtitleFull, subtitle.w());
        subtitleTruncated = !subtitleShown.equals(subtitleFull);
        Draw.scaledText(graphics, font, subtitleShown, subtitle.x(), baseline(subtitle, scale), scale,
                alpha(Slate.TEXT2, alpha));

        drawMp(graphics, font, scale, alpha);
        List<SkillView> views = SkillClientState.skills();
        String status = views.isEmpty()
                ? ClientText.get(SkillClientState.session() == null
                        ? ClientText.Keys.SKILL_SYNCING : ClientText.Keys.SKILL_EMPTY)
                : ClientText.get(ClientText.Keys.SKILL_PICK);
        Rect statusRect = layout.rect(STATUS);
        statusFull = status;
        String statusShown = ellipsis(font, status, statusRect.w());
        statusTruncated = !statusShown.equals(statusFull);
        Draw.scaledText(graphics, font, statusShown, statusRect.x(), baseline(statusRect, scale), scale,
                alpha(Slate.TEXT3, alpha));

        rowText.clear();
        Rect list = layout.rect(LIST);
        if (list.h() > 0) {
            graphics.enableScissor(list.x(), list.y(), list.right(), list.bottom());
            try {
                for (int slot = 0; slot < rowRects.size(); slot++) {
                    int dataIndex = scroll + slot;
                    if (dataIndex >= views.size()) break;
                    drawRow(graphics, font, scale, alpha, now, delta, rowRects.get(slot), views.get(dataIndex),
                            rowId(dataIndex), dataIndex);
                }
            } finally {
                graphics.disableScissor();
            }
            Rect bar = ScrollList.track(list, views.size(), rows, scale);
            if (bar.w() > 0) {
                Draw.fill(graphics, bar, alpha(Slate.SUNK, alpha));
                Rect thumb = ScrollList.thumb(bar, views.size(), rows, scroll, scale);
                if (thumb.h() > 0) Draw.fill(graphics, thumb, alpha(Slate.LINE, alpha));
            }
        }
        button(graphics, font, scale, BACK, ClientText.get(ClientText.Keys.BACK_TO_COMMANDS), true, alpha, now,
                delta);
    }

    /** MP value row, bar and the per-turn recovery line, split from the old single string. */
    private void drawMp(GuiGraphics graphics, Font font, double scale, double alpha) {
        Rect row = layout.rect(MP);
        int y = baseline(row, scale);
        Draw.scaledText(graphics, font, "MP", row.x(), y, scale, alpha(Slate.TEXT2, alpha));
        String ceiling = " / " + SkillClientState.number(BattleClientState.maxMp);
        String value = SkillClientState.number(BattleClientState.mp);
        int ceilingWidth = textWidth(font, ceiling);
        Draw.scaledText(graphics, font, ceiling, row.right() - ceilingWidth, y, scale, alpha(Slate.TEXT2, alpha));
        Draw.scaledText(graphics, font, value, row.right() - ceilingWidth - textWidth(font, value), y, scale,
                alpha(Slate.MP, alpha));
        Rect bar = layout.rect(MP_BAR);
        double fraction = BattleClientState.maxMp <= 0 ? 0.0D
                : Math.max(0.0D, Math.min(1.0D, BattleClientState.mp / BattleClientState.maxMp));
        Draw.bar(graphics, bar, alpha(Slate.MP, alpha), fraction);
        Rect recovery = layout.rect(MP_RECOVERY);
        Draw.scaledText(graphics, font,
                ClientText.get(ClientText.Keys.SKILL_MP_RECOVERY,
                        SkillClientState.number(BattleClientState.mpRecovery)),
                recovery.x(), baseline(recovery, scale), scale, alpha(Slate.TEXT2, alpha));
    }

    private void drawRow(GuiGraphics graphics, Font font, double scale, double alpha, long now, double delta, Rect rect,
                         SkillView view, String id, int dataIndex) {
        boolean enabled = view.ready();
        Rect box = rowSurface(graphics, id, rect, enabled, alpha, now, delta);
        boolean hovered = enabled && id.equals(hoveredId);
        Rect well = iconWell(graphics, box, hovered, alpha);
        com.tactical.neoforge.client.ui.platform.Draw.fill(graphics, well,
                alpha(hovered ? UiPalette.accentDark() : Slate.SUNK, alpha));
        item(graphics, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_BOOK), well);
        boolean selected = SkillClientState.selectedId() != null
                && SkillClientState.selectedId().equals(view.id());
        if (selected) {
            Draw.fill(graphics, box.x(), box.y(), layout.px(2), box.h(), alpha(UiPalette.accent(), alpha));
        }
        int textX = box.x() + layout.px(Motion.ICON_TEXT_LEFT);
        int right = box.right() - layout.px(4);
        int line = layout.px(Motion.LINE);
        int y = box.y() + layout.px(Motion.ICON_BOX_INSET);
        // Display point: the row's name is structure from the server and is resolved here, in this
        // client's own language, exactly once per draw.
        String name = view.name().getString() + " · Lv." + view.level() + " / " + view.maxLevel();
        String nameShown = ellipsis(font, name, right - textX);
        Draw.scaledText(graphics, font, nameShown, textX, y, scale,
                alpha(enabled ? Slate.TEXT : Slate.TEXT3, alpha));
        boolean shortened = !nameShown.equals(name);
        y += line;
        String description = view.description().getString();
        if (!narrow()) {
            String descriptionShown = ellipsis(font, description, right - textX);
            shortened |= !descriptionShown.equals(description);
            Draw.scaledText(graphics, font, descriptionShown, textX, y, scale,
                    alpha(!enabled ? Slate.TEXT3 : hovered ? Slate.TEXT : Slate.TEXT2, alpha));
            y += line;
        }
        // The price is the number this row exists for, so it is budgeted whole before the cooldown gets
        // any room: `15 AP · 10 MP` never loses a unit to a cooldown note that does not fit beside it.
        String price = view.targetCosts() ? ClientText.get(ClientText.Keys.SKILL_COST_BY_TARGET)
                : view.apCost() + " AP · " + SkillClientState.number(view.mpCost()) + " MP";
        String cooldown = ClientText.get(ClientText.Keys.SKILL_COOLDOWN, Integer.toString(view.cooldown()));
        TooltipText.PriceShown cost = TooltipText.priceWithSuffix(price, cooldown, right - textX, scale,
                font::width);
        shortened |= cost.suffixDropped();
        Draw.scaledText(graphics, font, cost.text(), textX, y, scale,
                alpha(enabled ? Slate.TEXT2 : Slate.TEXT3, alpha));
        y += line;
        String aim = enabled ? switch (view.aim()) {
            case SELF -> ClientText.get(ClientText.Keys.SKILL_AIM_SELF);
            case ENTITY -> ClientText.get(ClientText.Keys.SKILL_AIM_ENTITY);
            case GROUND -> ClientText.get(ClientText.Keys.SKILL_AIM_GROUND);
        } : view.reason().getString();
        String aimShown = ellipsis(font, aim, right - textX);
        shortened |= !aimShown.equals(aim);
        Draw.scaledText(graphics, font, aimShown, textX, y, scale,
                alpha(enabled ? Slate.TEXT2 : Slate.TEXT3, alpha));
        rowText.put(dataIndex, new RowText(name, description, price, cooldown, aim, shortened));
    }

    @Override
    public boolean scrollAt(double guiX, double guiY, double delta) {
        ensureLayout();
        Rect list = layout == null ? Rect.EMPTY : layout.rect(LIST);
        if (!list.contains(guiX, guiY)) return false;
        int total = SkillClientState.skills().size();
        if (!ScrollList.scrollable(total, rows)) return false;
        int next = ScrollList.scrollBy(scroll, total, rows, delta);
        if (next == scroll) return true;
        scroll = next;
        rebuild();
        return true;
    }

    @Override
    protected void activate(String id) {
        if (BACK.equals(id)) {
            back();
            return;
        }
        if (!id.startsWith("skill-row-")) return;
        int index;
        try {
            index = Integer.parseInt(id.substring("skill-row-".length()));
        } catch (NumberFormatException ignored) {
            return;
        }
        List<SkillView> views = SkillClientState.skills();
        if (index < 0 || index >= views.size()) return;
        // select() re-checks readiness and the player's turn; a disabled row therefore does nothing.
        SkillClientState.select(views.get(index).id());
    }

    @Override
    protected void back() {
        SkillMenu.back();
    }

    @Override
    protected String passiveHover(double guiX, double guiY) {
        if (subtitleTruncated && layout.rect(SUBTITLE).contains(guiX, guiY)) return SUBTITLE;
        if (statusTruncated && layout.rect(STATUS).contains(guiX, guiY)) return STATUS;
        return null;
    }

    protected String[] tooltipFor(String id) {
        // The two lines this page may shorten are not controls: they only gain a tooltip, never a hit.
        if (SUBTITLE.equals(id)) return subtitleTruncated && !subtitleFull.isEmpty()
                ? new String[]{subtitleFull} : null;
        if (STATUS.equals(id)) return statusTruncated && !statusFull.isEmpty()
                ? new String[]{statusFull} : null;
        int index = TooltipText.rowIndex(id, "skill-row-", SkillClientState.skills().size());
        if (index < 0) return null;
        RowText text = rowText.get(index);
        // The narrow form never draws the description line, so its rows always have something to add.
        if (text == null || (!text.shortened() && !narrow())) return null;
        // Two segments only: the shared tooltip takes a primary line and one supplementary group and
        // folds each of them itself, so the remaining fields travel as lines inside the second segment.
        return TooltipText.row(text.name(), text.description(), text.price() + text.cooldown(), text.aim());
    }

    /** The row ids currently on screen, in draw order, for the probe and the drivers. */
    public List<ResourceLocation> visibleSkillIds() {
        return List.copyOf(rowIds);
    }

    /**
     * Development probe: which skills this page is actually showing, in which row, and where its own
     * scroll sits. A driver needs that to say "the list really changed" instead of counting pixels.
     */
    @Override
    protected String probeExtra() {
        List<SkillView> views = SkillClientState.skills();
        StringBuilder skills = new StringBuilder("[");
        for (int index = 0; index < views.size(); index++) {
            SkillView view = views.get(index);
            if (index > 0) skills.append(',');
            // The displayed texts are resolved here on purpose: this is a display point, and a driver has to
            // be able to tell a language switch from a list change without reading pixels.
            skills.append("{\"row\":").append(index)
                    .append(",\"id\":\"").append(view.id()).append('\"')
                    .append(",\"ready\":").append(view.ready())
                    .append(",\"level\":").append(view.level())
                    .append(",\"name\":\"").append(escape(view.name().getString())).append('\"')
                    .append(",\"reason\":\"").append(escape(view.reason().getString())).append('\"')
                    .append(",\"cooldown\":").append(view.cooldown()).append('}');
        }
        skills.append(']');
        // pageCount/publication/revision are read-only diagnostics for the paged state: a driver needs to
        // know that the visible list really came from one assembled publication, not from a lucky frame.
        return "{\"scroll\":" + scroll + ",\"rows\":" + rows + ",\"count\":" + views.size()
                + ",\"pages\":" + SkillClientState.pageCount()
                + ",\"publication\":" + SkillClientState.publication()
                + ",\"revision\":" + SkillClientState.revision()
                + ",\"skills\":" + skills + "}";
    }

    private static String escape(String text) {
        if (text == null) return "";
        String quotes = String.valueOf((char) 34);
        String slash = String.valueOf((char) 92);
        StringBuilder safe = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            String piece = String.valueOf(current);
            if (piece.equals(quotes) || piece.equals(slash)) safe.append(slash).append(current);
            else if (current < 0x20) safe.append(' ');
            else safe.append(current);
        }
        return safe.toString();
    }

    /** Rows announced to the frame; the smart cast in the specification's whole-row rule. */
    public int visibleRows() {
        return rows;
    }
}
