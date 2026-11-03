package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.ScrollList;
import com.tactical.neoforge.client.ui.layout.TooltipText;
import com.tactical.neoforge.client.ui.panel.NativePanelScreen;
import com.tactical.neoforge.client.ui.panel.PanelLayout;
import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.neoforge.client.ui.platform.Draw;
import com.tactical.neoforge.client.ui.screen.TacticalSurface;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Slate;
import com.tactical.neoforge.network.NearbyBattlesPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "Nearby battles": the unparticipated B entry.
 *
 * <p>Every row is one encounter in this player's own dimension with its manager, distance, roster
 * and an explicit join reason. The page itself decides nothing: joining sends only the row's session
 * id, and a refusal refreshes the rows so a stale entry cannot silently join elsewhere. Dismissed rows
 * stay hidden until they become joinable again.</p>
 */
public final class NearbyBattlesScreen extends NativePanelScreen {

    public static final String SCREEN_ID = "nearby_battles";
    public static final String INFO = "nearby-info";
    public static final String LIST = "nearby-list";
    public static final String EMPTY = "nearby-empty";
    public static final String MESSAGE = "nearby-message";
    public static final String REFRESH = "nearby-refresh";
    public static final String CLOSE = "nearby-close";

    /** Row slots the page shows; the server caps its list at the same size. */
    public static final int ROWS = NearbyBattlesPacket.MAX_ENTRIES;
    private static final double ROW_HEIGHT = 49;
    private static final double ROW_HEIGHT_NARROW = 65;
    private static final int INFO_MAX_LINES = 3;
    private static final int MESSAGE_MAX_LINES = 2;

    private int scroll;
    private int visibleRows;
    private final List<Rect> rowRects = new ArrayList<>();
    private final List<Rect> joinRects = new ArrayList<>();
    private final List<Rect> dismissRects = new ArrayList<>();
    private final List<String> infoLines = new ArrayList<>();
    private final List<String> messageLines = new ArrayList<>();
    /**
     * Which rows this frame had to shorten, by data index: only those need a tooltip. Cleared at the
     * start of every frame's list drawing, so a row that scrolled away cannot answer for its replacement.
     */
    private final java.util.Set<Integer> shortenedRows = new java.util.HashSet<>();
    private String infoFull = "";
    private boolean infoShortened;
    private boolean emptyShortened;
    private String messageFull = "";
    private boolean messageShortened;

    private NearbyBattlesScreen() {
    }

    /** Open the page for a player who is not fighting; a fighter keeps the original B menu. */
    public static void open() {
        if (BattleClientState.active) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || !minecraft.player.isAlive()) return;
        // Never steal focus from an inventory, chat, death or another mod's screen.
        if (minecraft.screen != null && !(minecraft.screen instanceof TacticalSurface)) return;
        BattleDiscoveryClientState.undismissAll();
        BattleDiscoveryClientState.request();
        minecraft.setScreen(new NearbyBattlesScreen());
    }

    /** The native page reads live state every frame; kept for the existing tick hook. */
    public static void tickOpenScreen() {
    }

    /** Closing never joins anything: the list is simply forgotten until the next B press. */
    public static void close() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.screen instanceof NearbyBattlesScreen) minecraft.setScreen(null);
    }

    @Override
    public String screenId() {
        return SCREEN_ID;
    }

    @Override
    protected PanelLayout build(int guiWidth, int guiHeight, double scale, boolean narrow) {
        PanelLayout layout = PanelLayout.begin(guiWidth, guiHeight, scale, narrow);
        layout.title();
        layout.rule();
        int infoHeight = infoLineCount(layout) * Motion.LINE;
        layout.block(INFO, infoHeight);
        layout.gap(Motion.ROW_GAP);

        int rowHeight = layout.px(narrow ? ROW_HEIGHT_NARROW : ROW_HEIGHT);
        int gap = layout.px(Motion.ROW_GAP);
        List<NearbyBattlesPacket.Entry> listed = BattleDiscoveryClientState.visibleEntries();
        int footerReserve = layout.px(Motion.GROUP_GAP_ABOVE) + Scale.rule(layout.scale())
                + layout.px(Motion.GROUP_GAP_BELOW) + layout.px(Motion.BUTTON_HEIGHT) + layout.px(Motion.PAD);
        int messageReserve = messageLineCount(layout) * Motion.LINE;
        int budget = Math.max(0, layout.budgetToBottom() - footerReserve - messageReserve);
        int fits = ScrollList.rows(budget, rowHeight, gap);
        int visible = Math.min(listed.size(), fits);
        scroll = ScrollList.clamp(scroll, listed.size(), Math.max(1, visible));
        visibleRows = visible;
        rowRects.clear();
        joinRects.clear();
        dismissRects.clear();
        if (listed.isEmpty()) {
            layout.block(EMPTY, Motion.LINE);
        } else {
            int top = layout.cursor();
            int height = ScrollList.height(visible, rowHeight, gap);
            Rect list = Rect.of(layout.innerX(), top, layout.innerWidth(), height);
            layout.display(LIST, list);
            int pad = layout.px(Motion.ICON_BOX_INSET);
            int buttonWidth = layout.px(buttonDesignWidthWidest(ClientText.get(ClientText.Keys.NEARBY_JOIN),
                    ClientText.get(ClientText.Keys.NEARBY_DISMISS)));
            int buttonHeight = layout.px(Motion.BUTTON_HEIGHT);
            for (int slot = 0; slot < visible; slot++) {
                int dataIndex = scroll + slot;
                Rect rect = Rect.of(list.x(), list.y() + slot * (rowHeight + gap), list.w(), rowHeight);
                rowRects.add(rect);
                // The row's text area is a non-interactive region with its own id, so a row that had to cut
                // its reason can still be asked for the whole sentence. The two buttons keep their own ids
                // and their own priority; naming the row here never turns it into a third control.
                layout.display(rowId(dataIndex), rect);
                NearbyBattlesPacket.Entry entry = listed.get(dataIndex);
                Rect join;
                Rect dismiss;
                if (narrow) {
                    join = Rect.of(rect.x() + pad, rect.bottom() - pad - buttonHeight * 2 - gap,
                            rect.w() - pad * 2, buttonHeight);
                    dismiss = Rect.of(rect.x() + pad, rect.bottom() - pad - buttonHeight,
                            rect.w() - pad * 2, buttonHeight);
                } else {
                    dismiss = Rect.of(rect.right() - pad - buttonWidth, rect.bottom() - pad - buttonHeight,
                            buttonWidth, buttonHeight);
                    join = Rect.of(dismiss.x() - gap - buttonWidth, dismiss.y(), buttonWidth, buttonHeight);
                }
                joinRects.add(join);
                dismissRects.add(dismiss);
                // "Join" is a real control only while the server says the row is joinable; a disabled
                // one keeps its place, takes no hover and does nothing when clicked.
                if (entry.joinable()) layout.interactive(joinId(dataIndex), join);
                else layout.disabled(joinId(dataIndex), join);
                layout.interactive(dismissId(dataIndex), dismiss);
            }
            layout.moveTo(list.bottom());
        }
        layout.gap(Motion.ROW_GAP);
        layout.block(MESSAGE, messageLineCount(layout) * Motion.LINE);

        int buttonWidth = layout.px(buttonDesignWidthWidest(ClientText.get(ClientText.Keys.NEARBY_REFRESH),
                ClientText.get(ClientText.Keys.NEARBY_CLOSE)));
        if (narrow) {
            // Two full-width buttons stacked, so neither label has to be cut.
            layout.gap(Motion.GROUP_GAP_ABOVE);
            Rect rule = Rect.of(layout.innerX(), layout.cursor(), layout.innerWidth(), Scale.rule(layout.scale()));
            layout.display(PanelLayout.FOOTER_RULE, rule);
            layout.moveTo(rule.bottom() + layout.px(Motion.GROUP_GAP_BELOW));
            Rect refresh = Rect.of(layout.innerX(), layout.cursor(), layout.innerWidth(),
                    layout.px(Motion.BUTTON_HEIGHT));
            layout.interactive(REFRESH, refresh);
            layout.moveTo(refresh.bottom() + layout.px(Motion.ROW_GAP));
            Rect close = Rect.of(layout.innerX(), layout.cursor(), layout.innerWidth(), layout.px(Motion.BUTTON_HEIGHT));
            layout.interactive(CLOSE, close);
            layout.moveTo(close.bottom());
        } else {
            layout.footer(List.of(REFRESH, CLOSE), List.of(buttonWidth, buttonWidth), Motion.BUTTON_HEIGHT);
        }
        return layout.finish();
    }

    public static String rowId(int index) {
        return "row-" + index;
    }

    public static String joinId(int index) {
        return "row-" + index + "-join";
    }

    public static String dismissId(int index) {
        return "row-" + index + "-dismiss";
    }

    private int infoLineCount(PanelLayout layout) {
        int lines = com.tactical.neoforge.client.ui.layout.TextMetrics
                .wrap(infoText(), layout.innerWidth(), layout.scale(), Minecraft.getInstance().font::width).size();
        return Math.max(1, Math.min(INFO_MAX_LINES, lines));
    }

    private int messageLineCount(PanelLayout layout) {
        String message = message();
        if (message.isEmpty()) return 0;
        int lines = com.tactical.neoforge.client.ui.layout.TextMetrics
                .wrap(message, layout.innerWidth(), layout.scale(), Minecraft.getInstance().font::width).size();
        return Math.max(1, Math.min(MESSAGE_MAX_LINES, lines));
    }

    /** The page's one paragraph of prose: a key, resolved per frame so it wraps in its own language. */
    private static String infoText() {
        return ClientText.get(ClientText.Keys.NEARBY_INFO);
    }

    @Override
    protected void drawBody(GuiGraphics graphics, Font font, double scale, double alpha, long now, double delta) {
        drawTitle(graphics, font, scale, ClientText.get(ClientText.Keys.NEARBY_TITLE), alpha);
        drawRule(graphics);
        drawInfo(graphics, font, alpha);
        shortenedRows.clear();
        List<NearbyBattlesPacket.Entry> entries = BattleDiscoveryClientState.visibleEntries();
        if (entries.isEmpty()) {
            Rect empty = layout.rect(EMPTY);
            String emptyText = ClientText.get(ClientText.Keys.NEARBY_EMPTY);
            String emptyShown = ellipsis(font, emptyText, empty.w());
            emptyShortened = !emptyShown.equals(emptyText);
            Draw.scaledText(graphics, font, emptyShown, empty.x(), baseline(empty, scale), scale, alpha(Slate.TEXT3, alpha));
        } else {
            Rect list = layout.rect(LIST);
            graphics.enableScissor(list.x(), list.y(), list.right(), list.bottom());
            try {
                for (int slot = 0; slot < rowRects.size(); slot++) {
                    int dataIndex = scroll + slot;
                    if (dataIndex >= entries.size()) break;
                    drawRow(graphics, font, scale, alpha, now, delta, slot, dataIndex, entries.get(dataIndex));
                }
            } finally {
                graphics.disableScissor();
            }
            Rect bar = ScrollList.track(list, entries.size(), visibleRows, scale);
            if (bar.w() > 0) {
                Draw.fill(graphics, bar, alpha(Slate.SUNK, alpha));
                Rect thumb = ScrollList.thumb(bar, entries.size(), visibleRows, scroll, scale);
                if (thumb.h() > 0) Draw.fill(graphics, thumb, alpha(Slate.LINE, alpha));
            }
        }
        drawMessage(graphics, font, scale, alpha);
        button(graphics, font, scale, REFRESH, ClientText.get(ClientText.Keys.NEARBY_REFRESH), true, alpha, now,
                delta);
        button(graphics, font, scale, CLOSE, ClientText.get(ClientText.Keys.NEARBY_CLOSE), true, alpha, now, delta);
    }

    private void drawInfo(GuiGraphics graphics, Font font, double alpha) {
        Rect info = layout.rect(INFO);
        if (info.w() <= 0) return;
        infoLines.clear();
        infoFull = infoText();
        infoLines.addAll(wrap(font, infoFull, info.w()));
        int limit = Math.min(INFO_MAX_LINES, infoLines.size());
        infoShortened = infoLines.size() > INFO_MAX_LINES;
        for (int index = 0; index < limit; index++) {
            String line = infoLines.get(index);
            if (index == limit - 1 && infoLines.size() > INFO_MAX_LINES) line = line + "…";
            Draw.scaledText(graphics, font, line, info.x(), info.y() + index * layout.px(Motion.LINE),
                    layout.scale(), alpha(Slate.TEXT2, alpha));
        }
    }

    private void drawMessage(GuiGraphics graphics, Font font, double scale, double alpha) {
        Rect rect = layout.rect(MESSAGE);
        if (rect.w() <= 0) return;
        String message = message();
        messageLines.clear();
        messageFull = message;
        messageShortened = false;
        if (message.isEmpty()) return;
        messageLines.addAll(wrap(font, message, rect.w()));
        int limit = Math.min(MESSAGE_MAX_LINES, messageLines.size());
        messageShortened = messageLines.size() > MESSAGE_MAX_LINES;
        for (int index = 0; index < limit; index++) {
            String line = messageLines.get(index);
            if (index == limit - 1 && messageLines.size() > MESSAGE_MAX_LINES) line = line + "…";
            Draw.scaledText(graphics, font, line, rect.x(), rect.y() + index * layout.px(Motion.LINE), scale,
                    alpha(Slate.TEXT2, alpha));
        }
    }

    /** One encounter: manager, distance and roster, the join reason, then the two buttons. */
    private void drawRow(GuiGraphics graphics, Font font, double scale, double alpha, long now, double delta,
                         int slot, int dataIndex, NearbyBattlesPacket.Entry entry) {
        Rect rect = rowRects.get(slot);
        Draw.chip(graphics, rect, alpha(Slate.RAISE, alpha));
        int pad = layout.px(Motion.ICON_BOX_INSET);
        int textX = rect.x() + pad;
        int right = rect.right() - pad;
        int line = layout.px(Motion.LINE);
        int y = rect.y() + pad;
        String manager = managerText(entry);
        String managerShown = ellipsis(font, manager, right - textX);
        Draw.scaledText(graphics, font, managerShown, textX, y, scale,
                alpha(Slate.TEXT, alpha));
        y += line;
        String meta = metaText(entry);
        String metaShown = ellipsis(font, meta, right - textX);
        Draw.scaledText(graphics, font, metaShown, textX, y, scale, alpha(Slate.TEXT2, alpha));
        y += line;
        String reason = reasonText(entry);
        String reasonShown = ellipsis(font, reason, right - textX);
        Draw.scaledText(graphics, font, reasonShown, textX, y, scale,
                alpha(entry.joinable() ? Slate.ALLY : Slate.TEXT3, alpha));
        // Only a row that really had to cut something needs to explain itself.
        if (!managerShown.equals(manager) || !metaShown.equals(meta) || !reasonShown.equals(reason)) {
            shortenedRows.add(dataIndex);
        }

        drawButton(graphics, font, scale, joinId(dataIndex), ClientText.get(ClientText.Keys.NEARBY_JOIN),
                entry.joinable(), joinRects.get(slot), alpha, now, delta);
        drawButton(graphics, font, scale, dismissId(dataIndex), ClientText.get(ClientText.Keys.NEARBY_DISMISS),
                true, dismissRects.get(slot), alpha, now, delta);
    }

    /** Buttons are painted at their measured rectangle; the layout records them for hit testing. */
    private void drawButton(GuiGraphics graphics, Font font, double scale, String id, String label, boolean enabled,
                            Rect rect, double alpha, long now, double delta) {
        boolean hovered = enabled && id.equals(hoveredId);
        double progress = tween.progress(id, hovered, now, Motion.HOVER_MS, delta);
        boolean pressed = hovered && tween.pressActive(id, now);
        int base = !enabled ? Slate.SUNK : pressed ? Slate.SUNK : Draw.blend(Slate.RAISE, Slate.HOVER, progress);
        Draw.chip(graphics, rect, alpha(base, alpha));
        String text = ellipsis(font, label, rect.w() - layout.px(Motion.BUTTON_PAD_X) * 2);
        int width = textWidth(font, text);
        Draw.scaledText(graphics, font, text, rect.x() + (rect.w() - width) / 2,
                baseline(rect, scale) + (pressed ? 1 : 0), scale, alpha(enabled ? Slate.TEXT : Slate.TEXT3, alpha));
    }

    private static String message() {
        // Resolved here, at the drawing point: the mirror keeps the structure the server sent.
        Component message = BattleDiscoveryClientState.lastMessage();
        String last = message == null ? "" : message.getString();
        if (BattleDiscoveryClientState.awaiting()) {
            return reason(last, ClientText.get(ClientText.Keys.NEARBY_REFRESHING),
                    ClientText.get(ClientText.Keys.NEARBY_REFRESHING_SUFFIX));
        }
        if (BattleDiscoveryClientState.refreshPending()) {
            return reason(last, ClientText.get(ClientText.Keys.NEARBY_THROTTLED),
                    ClientText.get(ClientText.Keys.NEARBY_THROTTLED_SUFFIX));
        }
        return last == null ? "" : last;
    }

    /** Keep a real refusal reason visible next to the refresh state instead of replacing it. */
    private static String reason(String last, String refreshOnly, String suffix) {
        return last == null || last.isBlank() ? refreshOnly : last + suffix;
    }

    @Override
    public boolean scrollAt(double guiX, double guiY, double delta) {
        ensureLayout();
        Rect list = layout == null ? Rect.EMPTY : layout.rect(LIST);
        if (!list.contains(guiX, guiY)) return false;
        int entries = BattleDiscoveryClientState.visibleEntries().size();
        if (!ScrollList.scrollable(entries, visibleRows)) return false;
        int next = ScrollList.scrollBy(scroll, entries, visibleRows, delta);
        if (next == scroll) return true;
        scroll = next;
        rebuild();
        return true;
    }

    @Override
    protected void activate(String id) {
        if (REFRESH.equals(id)) {
            BattleDiscoveryClientState.request();
            return;
        }
        if (CLOSE.equals(id)) {
            close();
            return;
        }
        if (id.endsWith("-join")) join(slotOf(id, "-join"));
        else if (id.endsWith("-dismiss")) dismiss(slotOf(id, "-dismiss"));
    }

    private static int slotOf(String id, String suffix) {
        try {
            return Integer.parseInt(id.substring("row-".length(), id.length() - suffix.length()));
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private void join(int slot) {
        NearbyBattlesPacket.Entry entry = slot < 0 ? null : BattleDiscoveryClientState.rowAt(slot);
        if (entry == null || !entry.joinable()) {
            // Never guess another encounter; simply refresh so the reason is current.
            BattleDiscoveryClientState.request();
            return;
        }
        BattleDiscoveryClientState.joinRow(slot);
    }

    private void dismiss(int slot) {
        if (slot >= 0) BattleDiscoveryClientState.dismissRow(slot);
    }

    @Override
    protected void back() {
        close();
    }

    @Override
    protected String[] tooltipFor(String id) {
        // The page's own prose first: three places on this page really shorten what they draw.
        if (INFO.equals(id)) return infoShortened && !infoFull.isEmpty() ? new String[]{infoFull} : null;
        if (EMPTY.equals(id)) {
            return emptyShortened ? new String[]{ClientText.get(ClientText.Keys.NEARBY_EMPTY)} : null;
        }
        if (MESSAGE.equals(id)) return messageShortened && !messageFull.isEmpty() ? new String[]{messageFull} : null;
        // A row answers for exactly itself: the id carries the data index the row was drawn with, so a
        // button, the footer or another row can never inherit this row's refusal.
        List<NearbyBattlesPacket.Entry> entries = BattleDiscoveryClientState.visibleEntries();
        int index = TooltipText.rowIndex(id, "row-", entries.size());
        if (index < 0 || !shortenedRows.contains(index)) return null;
        NearbyBattlesPacket.Entry entry = entries.get(index);
        return TooltipText.row(managerText(entry), metaText(entry), reasonText(entry));
    }

    /**
     * The row's text area as a passive region: hovering it names the row without giving it a click. The
     * two buttons stay controls and win the hover first, and the footer keeps its own ids.
     */
    @Override
    protected String passiveHover(double guiX, double guiY) {
        for (int slot = 0; slot < rowRects.size(); slot++) {
            if (rowRects.get(slot).contains(guiX, guiY)) return rowId(scroll + slot);
        }
        Rect info = layout == null ? Rect.EMPTY : layout.rect(INFO);
        if (infoShortened && info.contains(guiX, guiY)) return INFO;
        Rect empty = layout == null ? Rect.EMPTY : layout.rect(EMPTY);
        if (emptyShortened && empty.contains(guiX, guiY)) return EMPTY;
        Rect message = layout == null ? Rect.EMPTY : layout.rect(MESSAGE);
        if (messageShortened && message.contains(guiX, guiY)) return MESSAGE;
        return null;
    }

    /** The manager's own name, or the page's own wording while the row carries none. */
    private static String managerText(NearbyBattlesPacket.Entry entry) {
        return entry.manager().isBlank() ? ClientText.get(ClientText.Keys.NEARBY_UNKNOWN_MANAGER) : entry.manager();
    }

    /** The row's distance and roster line, resolved at the drawing point. */
    private static String metaText(NearbyBattlesPacket.Entry entry) {
        return ClientText.get(ClientText.Keys.NEARBY_META, Integer.toString(entry.distance()),
                Integer.toString(entry.members()), Integer.toString(entry.capacity()));
    }

    /** Joining is worded by the page; a refusal keeps the server's own structure, resolved here. */
    private static String reasonText(NearbyBattlesPacket.Entry entry) {
        return entry.joinable() ? ClientText.get(ClientText.Keys.NEARBY_JOINABLE) : text(entry.reason());
    }

    /** One component of the row as this frame's text. */
    private static String text(Component text) {
        return text == null ? "" : text.getString();
    }

    /** Exposed for tests and drivers: the sessions currently listed, in row order. */
    public static List<UUID> listedSessions() {
        List<UUID> sessions = new ArrayList<>();
        for (NearbyBattlesPacket.Entry entry : BattleDiscoveryClientState.visibleEntries()) {
            sessions.add(entry.sessionId());
        }
        return sessions;
    }
}
