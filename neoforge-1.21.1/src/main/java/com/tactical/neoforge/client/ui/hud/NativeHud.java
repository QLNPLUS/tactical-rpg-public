package com.tactical.neoforge.client.ui.hud;

import com.tactical.neoforge.client.UiPalette;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.client.BattleAim;
import com.tactical.neoforge.client.BattleClientState;
import com.tactical.neoforge.client.BattleEntityLocator;
import com.tactical.neoforge.client.BattleLog;
import com.tactical.neoforge.client.BattleMode;
import com.tactical.neoforge.client.BattleUiRegions;
import com.tactical.neoforge.client.HudEntityTarget;
import com.tactical.neoforge.client.SkillClientState;
import com.tactical.neoforge.client.UiZoom;
import com.tactical.neoforge.client.ui.debug.DevProbe;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.ScrollList;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.platform.ClientResourceGeneration;
import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.neoforge.client.ui.platform.Draw;
import com.tactical.neoforge.client.ui.platform.Portraits;
import com.tactical.neoforge.client.ui.screen.NativeTacticalScreen;
import com.tactical.neoforge.client.ui.screen.TacticalSurface;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Palette;
import com.tactical.neoforge.client.ui.theme.Slate;
import com.tactical.neoforge.client.ui.widget.Tooltip;
import com.tactical.neoforge.client.ui.widget.Tween;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The four battle HUDs, drawn once per frame from one layout snapshot.
 *
 * <p>Native replacement for the former AUI overlays: the roster, the journal, the charge rail and
 * the passive nearby hint. Drawing, hover, wheel routing, click handling and the development probe
 * all read {@link #layout()} and {@link #model()}, so nothing can be drawn in one place and hit in
 * another.</p>
 *
 * <p>It is painted exactly once per frame from two mutually exclusive hooks: the GUI layer when no
 * screen is open, and the screen render when a tactical screen is open. Chat, containers, other mods'
 * screens and F1 all hide it, as before.</p>
 */
public final class NativeHud {

    /** Hover sources registered with the world locator; one per HUD surface. */
    public static final String SOURCE_ROSTER = "hud-roster";
    public static final String SOURCE_RAIL = "hud-rail";
    /** Probe ids of the two size handles, reported as their 12x12 hit areas. */
    public static final String HANDLE_ROSTER = "roster-handle";
    public static final String HANDLE_JOURNAL = "journal-handle";

    /** Height of the chat edit line a panel must stay above while a chat screen is open. */
    private static final int CHAT_INPUT_RESERVE = 12;

    private static final Tween TWEEN = new Tween();

    private static HudLayout layout;
    private static HudModel model = HudModel.empty();
    private static int rosterScroll;
    private static String hoveredId;
    /** The size tag drawn during a drag, so the probe can prove it covers no number. */
    private static Rect dragTag = Rect.EMPTY;
    /** Which candidate position the tag got, for the probe. */
    private static String dragTagWhere = "none";
    private static long lastFrame = Util.getMillis();
    private static long hintSince = Util.getMillis();

    /** Full text and drawn text of last frame, for the probe and the tooltips. */
    private static String weaponFull = "";
    private static String weaponShown = "";
    private static boolean weaponTruncated;
    private static final Map<String, String[]> ROW_TEXT = new java.util.LinkedHashMap<>();
    /** The size drag in flight, if any; it outranks every other HUD interaction. */
    private static HudDrag drag;
    private static Rect tooltipBox = Rect.EMPTY;
    private static List<String> tooltipLines = List.of();
    private static double lastAlpha = 1.0D;
    /**
     * Whether this frame's journal really painted its six-dot grip. Folded and expanded both set it;
     * the probe reports it so "the handle is drawn" is a machine-readable fact and not a guess.
     */
    private static boolean journalHandlePainted;

    /**
     * The press half of one entity-target gesture on a roster row or a rail portrait. It stores the
     * stable id and the whole action context, so a release can never act on whatever row happens to sit
     * under the pointer afterwards, and it is spent by the first release whatever the answer is.
     */
    private static HudEntityTarget.Capture pendingTarget;
    /** What the row under the pointer means right now, for the probe and the pressed visual. */
    private static HudEntityTarget.Action hoverAction = HudEntityTarget.Action.NONE;
    /** Why that row cannot be chosen; empty while it can. */
    private static String hoverBlocked = "";
    /**
     * The journal toggle's icon glyphs: the same marks the full wording ends with, drawn alone when the
     * title row has no room for the words. The whole wording then hangs on the button's tooltip.
     */
    /** The gap the roster footer keeps between its AP figures and the recovery line. */
    private static final int ROSTER_FOOTER_GAP = 6;

    private static final String TOGGLE_ICON_COLLAPSE = "−";
    private static final String TOGGLE_ICON_EXPAND = "+";
    /** The full toggle wording while the title row draws the icon button, null otherwise. */
    private static String journalToggleTooltip;
    /** The recovery line as worded, and whether the drawn form is shorter than it. */
    private static String recoveryFull = "";
    private static boolean recoveryTruncated;
    /** The journal's footer status as worded, and whether the drawn line is shorter than it. */
    private static String journalStatusFull = "";
    private static boolean journalStatusTruncated;

    private NativeHud() {
    }

    public static HudLayout layout() {
        return layout;
    }

    public static HudModel model() {
        return model;
    }

    public static String hoveredId() {
        return hoveredId;
    }

    // --------------------------------------------------------------- lifecycle

    /** Called every client tick: keeps hover, the world locator and the scroll clamps current. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        // A world change forgets the journal, which must happen even when no battle is active.
        BattleLog.tick();
        if (!BattleClientState.active) {
            close();
            return;
        }
        if (hidden(minecraft)) {
            clearHover();
            return;
        }
        updateLayout(minecraft);
        // A chat screen shows the left column as a picture only: no hover, so no world focus box and
        // no camera move behind the window the player is typing into.
        if (chatOpen(minecraft)) {
            clearHover();
            return;
        }
        updateHover(minecraft);
    }

    /** Battle over, disconnect or world change: every transient HUD state is dropped. */
    public static void close() {
        dragTag = Rect.EMPTY;
        if (drag != null) drag.clear();
        pendingTarget = null;
        clearHover();
        rosterScroll = 0;
        layout = null;
        model = HudModel.empty();
        TWEEN.clear();
    }

    private static void clearHover() {
        hoveredId = null;
        hoverAction = HudEntityTarget.Action.NONE;
        hoverBlocked = "";
        BattleEntityLocator.clear(SOURCE_ROSTER);
        BattleEntityLocator.clear(SOURCE_RAIL);
        tooltipBox = Rect.EMPTY;
        tooltipLines = List.of();
    }

    /**
     * Containers, hidden GUI and other mods' screens hide the whole HUD, as they always did. A chat
     * screen is the one passive exception: the left column may be seen over the chat messages, but
     * only as a picture - it owns no pointer, no wheel and no key there.
     */
    private static boolean hidden(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) return true;
        if (minecraft.options.hideGui) return true;
        return minecraft.screen != null && !(minecraft.screen instanceof TacticalSurface) && !chatOpen(minecraft);
    }

    /** A chat screen is open: the HUD may be painted, but never interacted with. */
    private static boolean chatOpen(Minecraft minecraft) {
        return minecraft != null && minecraft.screen instanceof ChatScreen;
    }

    /**
     * The chat edit line is the only part of the chat a panel may not cover: the history above it is
     * exactly what the left column is now allowed to reach over. Zero when no chat screen is open.
     */
    private static int chatInputReserve(Minecraft minecraft) {
        return chatOpen(minecraft) ? CHAT_INPUT_RESERVE : 0;
    }

    private static void updateLayout(Minecraft minecraft) {
        HudModel previous = model;
        model = HudModel.capture(minecraft);
        if (previous.hintText() == null && model.hintText() != null) hintSince = Util.getMillis();
        double scale = UiZoom.zoom();
        Scale.Form form = Scale.form(minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight(), scale);
        BattleLog.syncForm(form);
        int chatInputReserve = chatInputReserve(minecraft);
        int rows = model.roster().rows().size();
        // A drag is only valid for the frame it was measured in: a size preset change, a different
        // form or a window resize cancels it and restores the captured preference, never writes.
        HudDrag.Start dragStart = drag == null ? null : drag.start();
        if (dragStart != null && (Math.abs(dragStart.scale() - Scale.effective(scale)) > 1.0E-4D
                || dragStart.form() != form
                || dragStart.guiWidth() != minecraft.getWindow().getGuiScaledWidth()
                || dragStart.guiHeight() != minecraft.getWindow().getGuiScaledHeight())) {
            cancelDrag(minecraft);
        }
        layout = computeLayout(minecraft, form, scale, chatInputReserve, rows);
        // The layout clamps the requested offset to a window that really fills the list, and it is the
        // layout that decides how many rows are drawn: adopting its start keeps the state, the rows and
        // the bar on one window, and stops a taller panel or a shorter list from hiding the first
        // members behind an offset that nothing can scroll back from.
        rosterScroll = layout.rosterStart();
        if (layout.input().rosterScroll() != rosterScroll) {
            layout = computeLayout(minecraft, form, scale, chatInputReserve, rows);
            rosterScroll = layout.rosterStart();
        }
        // The body decides where the journal is scrolled to; adopting the resolved anchor keeps one
        // browsed position for every panel size and starts following the newest entry again as soon as
        // the view reaches the bottom of the stack.
        BattleLog.setJournalAnchor(layout.journalAnchor());
    }

    private static HudLayout computeLayout(Minecraft minecraft, Scale.Form form, double scale, int chatInputReserve,
                                           int rows) {
        boolean menuOpen = minecraft.screen instanceof TacticalSurface;
        return HudLayout.compute(new HudLayout.Input(
                minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight(), scale, form,
                chatInputReserve, rows > 0, rows, rosterScroll, journalMode(model, form), menuOpen,
                model.charge().ids(), model.charge().gauges(), model.charge().actorEntityId(), cramped(minecraft),
                hintWidth(minecraft), UiZoom.rosterWidth(), UiZoom.rosterHeight(), UiZoom.journalWidth(),
                UiZoom.journalHeight(), (budget, maxNameLines) -> rosterLines(minecraft, model, budget, maxNameLines),
                textWidth -> journalMeasure(minecraft, model, textWidth),
                BattleLog.journalAnchor()).withJournalChip(journalChipDesignWidth(minecraft, model))
                .withJournalToggle(journalToggleMeasure(minecraft, model)));
    }

    /**
     * The journal's chip column in 100% design pixels: the widest chip label this frame has to draw, plus
     * its own padding. Measuring the real labels is what keeps an English category ("Damage") whole while
     * the narrower Chinese one does not reserve more room than it asks for. The layout clamps the result.
     */
    static int journalChipDesignWidth(Minecraft minecraft, HudModel snapshot) {
        int widest = 0;
        for (HudModel.Entry entry : snapshot.journal().entries()) {
            String label = entry.label();
            if (label == null || label.isEmpty()) continue;
            widest = Math.max(widest, minecraft.font.width(label));
        }
        int design = widest + Motion.CHIP_PAD_X * 2;
        return Math.max(HudLayout.JOURNAL_CHIP_WIDTH, design);
    }

    /**
     * The title row's three real widths: the title, the whole toggle label and its single-glyph icon.
     * The layout uses them to decide whether the full label still fits beside the title.
     */
    static HudLayout.JournalToggle journalToggleMeasure(Minecraft minecraft, HudModel snapshot) {
        Font font = minecraft.font;
        String label = ClientText.get(JournalLabels.toggle(snapshot.journal().bodyShown()));
        String icon = snapshot.journal().bodyShown() ? TOGGLE_ICON_COLLAPSE : TOGGLE_ICON_EXPAND;
        return new HudLayout.JournalToggle(font.width(ClientText.get(ClientText.Keys.HUD_JOURNAL_TITLE)),
                font.width(label), font.width(icon));
    }

    /** The journal body is inline only in the standard form; elsewhere it floats above the world. */
    private static HudLayout.JournalMode journalMode(HudModel model, Scale.Form form) {
        if (!model.journal().visible()) return HudLayout.JournalMode.HIDDEN;
        if (!model.journal().bodyShown()) return HudLayout.JournalMode.HEADER;
        return form == Scale.Form.STANDARD ? HudLayout.JournalMode.EMBEDDED : HudLayout.JournalMode.FLOATING;
    }

    static boolean cramped(Minecraft minecraft) {
        return minecraft != null && minecraft.getWindow() != null
                && Scale.crampedFor(minecraft.getWindow().getGuiScaledWidth(), UiZoom.zoom());
    }

    private static int hintWidth(Minecraft minecraft) {
        if (model.hintText() == null) return 0;
        Font font = minecraft.font;
        double scale = Scale.effective(UiZoom.zoom());
        int text = TextMetrics.width(font.width(model.hintText()), scale);
        int width = text + Scale.effectivePx(Tooltip.PAD_X, scale) * 2;
        // The hint is truncated, never folded: a GUI too narrow simply cuts its tail.
        return Math.min(width, minecraft.getWindow().getGuiScaledWidth() - 2 * Scale.EDGE);
    }

    /**
     * The roster's line counts for one text-column width: the layout asks for the height it must
     * reserve, and the painter asks this same function so both see the same wrapping.
     */
    static int[] rosterLines(Minecraft minecraft, HudModel snapshot, int nameBudget, int maxNameLines) {
        List<HudModel.RosterRow> rows = snapshot.roster().rows();
        int[] lines = new int[rows.size()];
        double scale = Scale.effective(UiZoom.zoom());
        for (int index = 0; index < rows.size(); index++) {
            lines[index] = arrange(minecraft, rows.get(index), nameBudget, maxNameLines, scale).textLines();
        }
        return lines;
    }

    /** One roster row's text at the current width; shared by measurement and painting. */
    static RosterRowText.Arrangement arrange(Minecraft minecraft, HudModel.RosterRow row, int nameBudget,
                                             int maxNameLines, double scale) {
        String chip = row.current() ? ClientText.get(JournalLabels.ACTING) : "";
        return RosterRowText.arrange(row.name(), chip, row.health(), nameBudget, maxNameLines,
                scale, minecraft.font::width);
    }

    /**
     * The blocked-reason tooltip's wording: the routing returns a key, and this display point resolves it
     * in the player's own language. No key means the row is choosable, which the caller already handles.
     */
    static String blockedReasonText(String key) {
        return key == null || key.isEmpty() ? "" : ClientText.get(key);
    }

    /**
     * The retained history's line counts for one text-column width (newest first) with the entries'
     * own sequence numbers (oldest first). The layout stacks them in pixels and resolves the browsed
     * anchor against them; the painter wraps the same text at the same width.
     */
    static HudLayout.JournalMeasure journalMeasure(Minecraft minecraft, HudModel snapshot, int textWidth) {
        List<HudModel.Entry> entries = snapshot.journal().entries();
        int[] lines = new int[entries.size()];
        long[] sequences = new long[entries.size()];
        double scale = Scale.effective(UiZoom.zoom());
        for (int index = 0; index < entries.size(); index++) {
            HudModel.Entry entry = entries.get(entries.size() - 1 - index);
            lines[index] = Math.max(1, TextMetrics.wrap(entry.text(), textWidth, scale, minecraft.font::width).size());
            sequences[index] = entries.get(index).sequence();
        }
        return new HudLayout.JournalMeasure(lines, sequences);
    }

    private static Minecraft minecraft() {
        return Minecraft.getInstance();
    }

    // --------------------------------------------------------------- entity targets

    /** The aim a selected skill declares, or null when no skill is selected. */
    private static com.tactical.neoforge.skill.SkillDefinition.Aim skillAim() {
        var view = SkillClientState.selected();
        return view == null ? null : view.aim();
    }

    /**
     * True while an aiming page is open for a mode that can target one entity. Only then do roster rows
     * and portraits become clickable targets; with no page open the HUD keeps its plain hover look.
     */
    static boolean entityTargeting() {
        return BattleAim.isOpen() && HudEntityTarget.entityMode(BattleClientState.mode, skillAim());
    }

    /**
     * The row as the client can see it: the stable id comes from the loaded world entity, never from the
     * numeric id alone, so a recycled id can never be mistaken for the entity the press captured.
     */
    private static HudEntityTarget.Row rowFor(int entityId) {
        LivingEntity entity = living(entityId);
        if (entity == null) return HudEntityTarget.Row.UNKNOWN;
        return new HudEntityTarget.Row(entity.getUUID(), entity.isAlive() && !entity.isRemoved(),
                entity instanceof net.minecraft.world.entity.Mob,
                BattleClientState.participantHealth.containsKey(entityId),
                minecraft().player == entity);
    }

    private static HudEntityTarget.Action actionFor(int entityId) {
        if (!entityTargeting()) return HudEntityTarget.Action.NONE;
        return HudEntityTarget.action(BattleClientState.mode, skillAim(), rowFor(entityId));
    }

    /** The identity of the command itself: the selected skill's id for a cast, empty for everything else. */
    private static String selection() {
        var id = SkillClientState.selectedId();
        return BattleClientState.mode == BattleMode.SKILL && id != null ? id.toString() : "";
    }

    /**
     * The entity a press at that point belongs to: a roster row first, then a portrait, because the
     * portrait is drawn on top of the band and is the last thing under the pointer (the same order the
     * hover uses). The roster's scroll bar keeps its own gesture and is never a row.
     */
    private static int targetEntityAt(double guiX, double guiY) {
        if (layout == null || !layout.overUi(guiX, guiY)) return -1;
        int entityId = -1;
        if (!layout.rosterBar().contains(guiX, guiY)) {
            for (int slot = 0; slot < layout.rosterRows(); slot++) {
                if (!layout.rosterRow(slot).contains(guiX, guiY)) continue;
                int index = rosterScroll + slot;
                if (index < model.roster().rows().size()) entityId = model.roster().rows().get(index).entityId();
                break;
            }
        }
        HudLayout.Portrait portrait = layout.portraitAt(guiX, guiY);
        if (portrait != null) entityId = portrait.entityId();
        return entityId;
    }

    /** Captures one press on a choosable row; a row with no action captures nothing. */
    private static void captureTarget(int entityId) {
        HudEntityTarget.Action action = actionFor(entityId);
        if (action == HudEntityTarget.Action.NONE) {
            pendingTarget = null;
            return;
        }
        pendingTarget = new HudEntityTarget.Capture(rowFor(entityId).id(), action, BattleClientState.sessionId,
                BattleClientState.turnEpoch, BattleClientState.mode, skillAim(), selection(),
                BattleClientState.selectedSlot);
        // The pressed state belongs to the surface that was actually pressed, so it sinks that one row.
        String roster = "roster-" + entityId;
        String rail = "rail-" + entityId;
        TWEEN.pressed(roster.equals(hoveredId) || rail.equals(hoveredId) ? hoveredId : roster, Util.getMillis());
    }

    /**
     * Executes the captured action when the release still belongs to the same target and the same battle
     * state, then always drops the capture: one press/release pair can act at most once.
     */
    private static boolean releaseTarget(double guiX, double guiY) {
        HudEntityTarget.Capture capture = pendingTarget;
        pendingTarget = null;
        if (capture == null) return false;
        Minecraft minecraft = minecraft();
        if (layout == null || hidden(minecraft) || chatOpen(minecraft) || !entityTargeting()) return true;
        // A page layer above the HUD owns the pointer now: the press belongs to the row it started on.
        if (minecraft.screen instanceof NativeTacticalScreen surface && surface.overUi(guiX, guiY)) return true;
        int entityId = targetEntityAt(guiX, guiY);
        HudEntityTarget.Row row = entityId >= 0 ? rowFor(entityId) : HudEntityTarget.Row.UNKNOWN;
        HudEntityTarget.Action action = HudEntityTarget.action(BattleClientState.mode, skillAim(), row);
        if (!capture.release(action, row, BattleClientState.sessionId, BattleClientState.turnEpoch,
                BattleClientState.mode, skillAim(), selection(), BattleClientState.selectedSlot)) {
            return true;
        }
        BattleAim.executeEntityTarget(minecraft, capture.id());
        return true;
    }

    /** A capture never survives a lost focus, a hidden HUD, an open chat or a closed aiming page. */
    private static void dropStaleTarget(Minecraft minecraft) {
        if (pendingTarget == null) return;
        if (!minecraft.isWindowActive() || layout == null || hidden(minecraft) || chatOpen(minecraft)
                || !entityTargeting()) {
            pendingTarget = null;
        }
    }

    // --------------------------------------------------------------- hover

    private static void updateHover(Minecraft minecraft) {
        if (layout == null) return;
        if (dragging()) {
            hoveredId = drag.target() == HudDrag.Target.ROSTER ? HudLayout.ROSTER_HANDLE : HudLayout.JOURNAL_HANDLE;
            hoverAction = HudEntityTarget.Action.NONE;
            hoverBlocked = "";
            BattleEntityLocator.clear(SOURCE_ROSTER);
            BattleEntityLocator.clear(SOURCE_RAIL);
            return;
        }
        double[] cursor = BattleUiRegions.cursorGui();
        // A layer of the open page above the HUD (the gear popover, the colour editor) owns the
        // pointer: nothing behind it may light up and shift the camera.
        if (minecraft.screen instanceof NativeTacticalScreen surface && surface.overUi(cursor[0], cursor[1])) {
            hoveredId = null;
            hoverAction = HudEntityTarget.Action.NONE;
            hoverBlocked = "";
            BattleEntityLocator.clear(SOURCE_ROSTER);
            BattleEntityLocator.clear(SOURCE_RAIL);
            return;
        }
        String hover = null;
        int rosterId = -1;
        int railId = -1;
        if (layout.overUi(cursor[0], cursor[1])) {
            for (int slot = 0; slot < layout.rosterRows(); slot++) {
                if (layout.rosterRow(slot).contains(cursor[0], cursor[1])) {
                    int index = rosterScroll + slot;
                    if (index < model.roster().rows().size()) {
                        rosterId = model.roster().rows().get(index).entityId();
                        hover = "roster-" + rosterId;
                    }
                    break;
                }
            }
            HudLayout.Portrait portrait = layout.portraitAt(cursor[0], cursor[1]);
            if (portrait != null) {
                railId = portrait.entityId();
                hover = "rail-" + railId;
            }
            if (hover == null && layout.journalToggle().contains(cursor[0], cursor[1])) {
                hover = HudLayout.JOURNAL_TOGGLE;
            }
            if (hover == null && layout.rosterFooterWeapon().contains(cursor[0], cursor[1])) {
                hover = HudLayout.ROSTER_FOOTER_WEAPON;
            }
            if (hover == null && recoveryTruncated
                    && layout.rosterFooterAp().contains(cursor[0], cursor[1])) {
                hover = HudLayout.ROSTER_FOOTER_AP;
            }
            if (hover == null && journalStatusTruncated
                    && layout.journalStatus().contains(cursor[0], cursor[1])) {
                hover = HudLayout.JOURNAL_STATUS;
            }
            // The handles outrank the rows they overlap: their hit area is the panel's corner.
            if (layout.journalHandleHit().contains(cursor[0], cursor[1])) hover = HudLayout.JOURNAL_HANDLE;
            if (layout.rosterHandleHit().contains(cursor[0], cursor[1])) hover = HudLayout.ROSTER_HANDLE;
        }
        hoveredId = hover;
        // The handle and the scroll bar outrank the row they overlap, so an entity-target row is only
        // reported when the pointer is really on that row.
        int targetId = -1;
        if (hover != null && hover.equals("rail-" + railId) && railId >= 0) targetId = railId;
        else if (hover != null && hover.equals("roster-" + rosterId) && rosterId >= 0) targetId = rosterId;
        hoverAction = targetId < 0 ? HudEntityTarget.Action.NONE : actionFor(targetId);
        hoverBlocked = targetId < 0 || hoverAction != HudEntityTarget.Action.NONE ? ""
                : blockedReasonText(HudEntityTarget.blockedReason(BattleClientState.mode, skillAim(),
                        rowFor(targetId)));
        // The locator is the single source of the world hover box: roster and rail feed the same id.
        if (rosterId >= 0) BattleEntityLocator.hover(SOURCE_ROSTER, rosterId);
        else BattleEntityLocator.clear(SOURCE_ROSTER);
        if (railId >= 0) BattleEntityLocator.hover(SOURCE_RAIL, railId);
        else BattleEntityLocator.clear(SOURCE_RAIL);
    }

    // --------------------------------------------------------------- input

    /** True when the pointer is over a HUD surface, i.e. camera gestures belong to the HUD there. */
    public static boolean overUi() {
        Minecraft minecraft = minecraft();
        if (layout == null || hidden(minecraft) || chatOpen(minecraft)) return false;
        double[] cursor = BattleUiRegions.cursorGui();
        return layout.overUi(cursor[0], cursor[1]);
    }

    public static boolean overUi(double guiX, double guiY) {
        Minecraft minecraft = minecraft();
        if (layout == null || hidden(minecraft) || chatOpen(minecraft)) return false;
        return layout.overUi(guiX, guiY);
    }

    /** True when a HUD control consumed a click: only the journal's toggle ever does. */
    public static boolean consumesClick(double guiX, double guiY) {
        Minecraft minecraft = minecraft();
        if (layout == null || hidden(minecraft) || chatOpen(minecraft)) return false;
        return layout.journalToggle().contains(guiX, guiY);
    }

    /**
     * True when a press here really operates a HUD control - a size handle, the journal's toggle, or a
     * size drag that already owns the pointer - instead of merely being swallowed by a panel.
     *
     * <p>The input layer asks this before the hotbar. A visible grip drawn over the vanilla band is the
     * upper layer, so its press must start the drag it was aimed at; a press anywhere else keeps the
     * old order, where the hotbar is asked before the rest of the HUD. A hidden or chat-covered HUD
     * owns nothing here, which is what keeps a painted-but-passive frame out of the way.</p>
     */
    public static boolean claimsPress(double guiX, double guiY) {
        Minecraft minecraft = minecraft();
        if (layout == null || hidden(minecraft) || chatOpen(minecraft)) return false;
        if (dragging()) return true;
        return handleAt(guiX, guiY) != null || layout.journalToggle().contains(guiX, guiY);
    }

    /**
     * HUD press handling, the first link of the click chain. The size handles come first: a press on
     * one starts a drag and nothing else may act for the rest of it. The journal's toggle comes next,
     * then an entity-target row, and only then the rest of the HUD, which swallows the press without
     * acting. A plain mode never turns a row into an action.
     */
    public static boolean click(double guiX, double guiY, int button) {
        Minecraft minecraft = minecraft();
        if (layout == null || hidden(minecraft) || chatOpen(minecraft)) return false;
        if (button != 0) return layout.overUi(guiX, guiY);
        HudDrag.Target target = handleAt(guiX, guiY);
        if (target != null) {
            beginDrag(target, guiX, guiY);
            return true;
        }
        if (!layout.overUi(guiX, guiY)) return false;
        if (layout.journalToggle().contains(guiX, guiY)) {
            TWEEN.pressed(HudLayout.JOURNAL_TOGGLE, Util.getMillis());
            BattleLog.toggleBody();
            updateLayout(minecraft());
            return true;
        }
        if (entityTargeting()) captureTarget(targetEntityAt(guiX, guiY));
        return true;
    }

    /**
     * Pointer motion. While a size drag runs the proposed geometry is applied to the preferences in
     * memory only - one write happens when the pointer is released - and the frame's layout is rebuilt
     * from it. The pointer position is the only thing this uses, so it behaves the same with or
     * without a screen open.
     */
    public static boolean dragged(double guiX, double guiY) {
        if (drag == null || !drag.active()) return false;
        Minecraft minecraft = minecraft();
        if (hidden(minecraft)) {
            cancelDrag(minecraft);
            return false;
        }
        Integer width = drag.width(guiX);
        Integer height = drag.height(guiY);
        if (width == null && height == null) return true;
        HudDrag.Start start = drag.start();
        // Only the axis the pointer really left its press position on is written: a horizontal drag
        // must not turn an AUTO height into the height this frame happened to have.
        Integer nextWidth = width == null ? start.savedWidth() : width;
        Integer nextHeight = height == null ? start.savedHeight() : height;
        if (drag.target() == HudDrag.Target.ROSTER) {
            UiZoom.setRosterSize(nextWidth, nextHeight);
        } else {
            UiZoom.setJournalSize(nextWidth, nextHeight);
        }
        updateLayout(minecraft);
        return true;
    }

    /**
     * Pointer release: an entity-target press ends here first, because it owns the whole gesture the HUD
     * swallowed, then a drag that really resized writes its result exactly once.
     */
    public static boolean release(double guiX, double guiY, int button) {
        if (button != 0) {
            pendingTarget = null;
            return false;
        }
        if (releaseTarget(guiX, guiY)) return true;
        if (drag != null && drag.active()) {
            dragged(guiX, guiY);
            // A press released where it landed resized nothing, so it must not touch the file either.
            boolean resized = drag.moved();
            drag.end();
            if (resized) UiZoom.commit();
            updateLayout(minecraft());
            return true;
        }
        if (drag != null && drag.takeSwallowedRelease()) return true;
        return false;
    }

    /** True while a size drag owns the pointer: the wheel and the click chain both respect it. */
    public static boolean dragging() {
        return drag != null && drag.active();
    }

    private static HudDrag.Target handleAt(double guiX, double guiY) {
        if (layout.journalHandleHit().contains(guiX, guiY)
                && (layout.journalMode() != HudLayout.JournalMode.HIDDEN)) {
            return HudDrag.Target.JOURNAL;
        }
        if (layout.rosterHandleHit().contains(guiX, guiY) && layout.rosterPanel().w() > 0) {
            return HudDrag.Target.ROSTER;
        }
        return null;
    }

    private static void beginDrag(HudDrag.Target target, double guiX, double guiY) {
        Minecraft minecraft = minecraft();
        double scale = Scale.effective(UiZoom.zoom());
        boolean journal = target == HudDrag.Target.JOURNAL;
        int widthPx = journal ? layout.journalWidth() : layout.rosterWidth();
        int heightPx = journal ? layout.journalBodyHeight() : layout.rosterPanel().h();
        if (drag == null) drag = new HudDrag();
        drag.begin(new HudDrag.Start(target, guiX, guiY, scale,
                Scale.form(minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight(),
                        UiZoom.zoom()),
                minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight(),
                widthPx, heightPx,
                journal ? UiZoom.journalWidth() : UiZoom.rosterWidth(),
                journal ? UiZoom.journalHeight() : UiZoom.rosterHeight()));
    }

    /**
     * Drops the drag and puts the preference captured at the press back: a cancelled drag never
     * changes what is saved, and the release that follows is swallowed instead of reaching the world.
     */
    public static void cancelDrag(Minecraft minecraft) {
        if (drag == null || !drag.active()) return;
        HudDrag.Start start = drag.start();
        if (start.target() == HudDrag.Target.ROSTER) {
            UiZoom.setRosterSize(start.savedWidth(), start.savedHeight());
        } else {
            UiZoom.setJournalSize(start.savedWidth(), start.savedHeight());
        }
        drag.cancel();
        layout = null;
        if (minecraft != null) updateLayout(minecraft);
    }

    /**
     * Wheel routing, first link of the chain (journal history, then scrollable lists, then the
     * hotbar, then the world). The journal consumes the wheel even at either end of its history, so
     * the camera never receives it while the pointer is over the body.
     *
     * <p>Both HUD lists share one direction, which is what the user asked for: the roster turns the
     * wheel exactly like the battle journal. A notch down - a negative delta - browses towards the
     * newer end, so the entries already on screen move up; a notch up moves them down. Both therefore
     * receive the raw notch and nothing flips a sign ({@link HudWheel#rosterOffset} is the roster's
     * one call site), and the stored history still keeps its oldest-first order.</p>
     */
    public static boolean scroll(double guiX, double guiY, double delta) {
        Minecraft minecraft = minecraft();
        if (layout == null || hidden(minecraft) || chatOpen(minecraft)) return false;
        // A drag owns the wheel too: nothing may leak to the hotbar or the camera while it runs.
        if (dragging()) return true;
        if (layout.rosterArea().contains(guiX, guiY) && layout.rosterScrollable()) {
            int rows = model.roster().rows().size();
            rosterScroll = HudWheel.rosterOffset(rosterScroll, rows, layout.rosterRows(), delta);
            updateLayout(minecraft());
            return true;
        }
        if (BattleLog.bodyShown() && (layout.journalBody().contains(guiX, guiY)
                || layout.journalFooterRule().contains(guiX, guiY) || layout.journalStatus().contains(guiX, guiY))) {
            // The viewport's own pixel geometry is what a notch moves through, so a single entry
            // taller than the body can be read line by line instead of being skipped whole.
            if (BattleLog.scrollHistory(delta, layout.journalContent())) {
                updateLayout(minecraft());
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------------- drawing

    /** Draws the whole HUD. Called exactly once per frame by the two exclusive hooks below. */
    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = minecraft();
        if (!BattleClientState.active || hidden(minecraft)) {
            clearHover();
            pendingTarget = null;
            // A frame that draws nothing must not leave the last frame's rectangles in the probe:
            // a reviewer has to see "hidden", not a stale roster that is no longer on screen.
            if (DevProbe.enabled()) DevProbe.publishHud(hiddenJson());
            return;
        }
        updateLayout(minecraft);
        journalHandlePainted = false;
        dropStaleTarget(minecraft);
        // A chat screen is passive: a press never starts a drag there, and one that was running when
        // the chat opened is cancelled rather than carried over the player's typing.
        if (chatOpen(minecraft) && dragging()) cancelDrag(minecraft);
        // A drag owns the pointer: sample it every frame, which is the one code path that works with
        // and without a screen open, and drop it when the window stops being active.
        if (dragging()) {
            double[] dragCursor = BattleUiRegions.cursorGui();
            dragged(dragCursor[0], dragCursor[1]);
        }
        if (dragging() && !minecraft.isWindowActive()) cancelDrag(minecraft);
        // The size tag only exists while a drag runs; a stale rectangle must not be reported.
        if (!dragging()) dragTag = Rect.EMPTY;
        // A chat screen is passive: the frame is painted, but the pointer belongs to the chat.
        if (chatOpen(minecraft)) clearHover();
        else updateHover(minecraft);
        long now = Util.getMillis();
        double delta = Math.max(0.0D, Math.min(100.0D, now - lastFrame));
        lastFrame = now;
        lastAlpha = 1.0D;
        drawRoster(graphics, now, delta);
        drawJournal(graphics, now, delta);
        drawRail(graphics, now, delta);
        drawHint(graphics, now);
        // The tooltip and the size tag are one floating layer, and being drawn last is not enough:
        // the rail's portraits are entity models standing 450 deep, so a tooltip whose trigger is one
        // of those models was painted into the plane *behind* it and came out as a black strip with
        // its text buried. The layer therefore gets a named plane of its own - above every model it
        // can overlap, below the modal overlay the open pages draw at TOP_OVERLAY_DEPTH.
        Draw.atDepth(graphics, TacticalSurface.HUD_FLOAT_DEPTH, () -> {
            drawTooltip(graphics, now);
            drawDragTag(graphics);
        });
        if (DevProbe.enabled()) DevProbe.publishHud(probeJson());
    }

    private static void drawRoster(GuiGraphics graphics, long now, double delta) {
        Rect panel = layout.rosterPanel();
        if (panel.w() <= 0 || panel.h() <= 0) return;
        Font font = minecraft().font;
        double scale = layout.scale();
        Draw.shadow(graphics, panel, Scale.PANEL_SHADOW, Slate.SHADOW, true);
        Draw.slab(graphics, panel, Slate.SLAB);

        HudModel.Vitals vitals = model.vitals();
        Rect title = layout.rosterTitle();
        int titleY = baseline(title, scale);
        String roundLabel = ClientText.get(ClientText.Keys.HUD_ROUND_LABEL);
        String roundNumber = Integer.toString(vitals.round());
        int roundWidth = textWidth(font, roundLabel) + textWidth(font, roundNumber);
        Draw.scaledText(graphics, font, roundLabel, title.x(), titleY, scale, Slate.TEXT2);
        Draw.scaledText(graphics, font, roundNumber, title.x() + textWidth(font, roundLabel), titleY, scale,
                Slate.TEXT);
        // "Round n" stays whole; the actor's name is what gives way, with a real gap left between them.
        String actor = vitals.hasActor() ? vitals.actorName() : ClientText.get(ClientText.Keys.HUD_ACTOR_WAITING);
        int actorBudget = Math.max(1, title.right() - (title.x() + roundWidth) - Scale.effectivePx(6, scale));
        int actorWidth = textWidth(font, actor);
        if (actorWidth > actorBudget) {
            actor = ellipsis(font, actor, actorBudget);
            actorWidth = textWidth(font, actor);
        }
        Draw.scaledText(graphics, font, actor, title.right() - actorWidth, titleY, scale,
                vitals.hasActor() ? Slate.TEXT : Slate.TEXT3);
        Draw.accentRule(graphics, layout.rosterRule());

        Rect area = layout.rosterArea();
        graphics.enableScissor(area.x(), area.y(), area.right(), area.bottom());
        try {
            for (int slot = 0; slot < layout.rosterRows(); slot++) {
                int index = rosterScroll + slot;
                if (index >= model.roster().rows().size()) break;
                drawRosterRow(graphics, font, scale, layout.rosterRow(slot), model.roster().rows().get(index), now,
                        delta);
            }
        } finally {
            graphics.disableScissor();
        }
        // A list whose last row did not fit leaves a sub-row gap: it is filled with the panel surface
        // instead of showing the world through a half row.
        Rect tail = layout.rosterBlankTail();
        if (tail.h() > 0) Draw.slab(graphics, tail, Slate.SLAB);
        drawScrollBar(graphics, layout.rosterArea(), layout.rosterBar(), model.roster().rows().size(),
                layout.rosterRows(), rosterScroll);
        drawHandle(graphics, font, scale, HudLayout.ROSTER, layout.rosterHandle(), layout.rosterHandleHit(),
                layout.rosterWidth(), rosterHeightValue(), now, delta);

        if (layout.rosterFooterRule().w() <= 0) return;
        Draw.separator(graphics, layout.rosterFooterRule());
        Rect ap = layout.rosterFooterAp();
        int apY = baseline(ap, scale);
        String apLabel = "AP ";
        Draw.scaledText(graphics, font, apLabel, ap.x(), apY, scale, Slate.TEXT2);
        String apValue = vitals.ap() + "";
        Draw.scaledText(graphics, font, apValue, ap.x() + textWidth(font, apLabel), apY, scale, Slate.AP);
        String apRest = " / " + Math.max(0, vitals.maxAp());
        Draw.scaledText(graphics, font, apRest, ap.x() + textWidth(font, apLabel) + textWidth(font, apValue), apY,
                scale, Slate.TEXT2);
        // The recovery line shares the footer with the AP figures, so it gets its own budget instead of
        // being drawn from the right edge over them. Its number is the part that may never be dropped:
        // the wording is what goes first, and the whole line stays readable in the tooltip.
        String apFigures = apValue + apRest;
        int figureEnd = ap.x() + textWidth(font, apLabel) + textWidth(font, apFigures);
        int recoveryBudget = Math.max(0, ap.right() - figureEnd - Scale.effectivePx(ROSTER_FOOTER_GAP, scale));
        recoveryFull = vitals.defending() ? ClientText.get(ClientText.Keys.HUD_DEFENDING)
                : ClientText.get(ClientText.Keys.HUD_RECOVERY, Integer.toString(vitals.apRecovery()));
        String recovery = recoveryFull;
        if (textWidth(font, recovery) > recoveryBudget) {
            // Drop the words, keep the number: "+50" still says what the line is about.
            String amount = ClientText.get(ClientText.Keys.HUD_RECOVERY_AMOUNT,
                    Integer.toString(vitals.apRecovery()));
            recovery = vitals.defending() || textWidth(font, amount) > recoveryBudget ? "" : amount;
        }
        recoveryTruncated = !recovery.equals(recoveryFull);
        if (!recovery.isEmpty()) {
            Draw.scaledText(graphics, font, recovery, ap.right() - textWidth(font, recovery), apY, scale,
                    vitals.defending() ? Slate.ALLY : Slate.TEXT2);
        }

        Rect weapon = layout.rosterFooterWeapon();
        weaponFull = model.vitals().weaponText();
        int weaponY = baseline(weapon, scale);
        weaponShown = ellipsis(font, weaponFull, weapon.w());
        weaponTruncated = !weaponShown.equals(weaponFull);
        Draw.scaledText(graphics, font, weaponShown, weapon.x(), weaponY, scale, Slate.TEXT2);
    }

    private static void drawRosterRow(GuiGraphics graphics, Font font, double scale, Rect row,
                                      HudModel.RosterRow data, long now, double delta) {
        String id = "roster-" + data.entityId();
        boolean hovered = id.equals(hoveredId);
        // Entity-target states: a choosable row carries a permanent accent bar, an unavailable one
        // turns its text neutral and never lifts, and a press sinks it instead.
        boolean targeting = entityTargeting();
        HudEntityTarget.Action action = targeting ? actionFor(data.entityId()) : HudEntityTarget.Action.NONE;
        boolean selectable = action != HudEntityTarget.Action.NONE;
        boolean unavailable = targeting && !selectable;
        double progress = TWEEN.progress(id, hovered, now, Motion.HOVER_MS, delta);
        boolean pressed = hovered && selectable && TWEEN.pressActive(id, now);
        int rise = pressed ? 1 : hovered && !unavailable && progress >= 0.5D ? -1 : 0;
        Rect box = row.move(0, rise);
        if (pressed) {
            Draw.chip(graphics, box, Slate.SUNK);
        } else if (progress > 0.0D) {
            Draw.chip(graphics, box, Motion.withAlpha(Slate.HOVER, progress));
        }
        if (data.current() || selectable) {
            int width = Scale.effectivePx(selectable && hovered ? 3 : 2, scale);
            // The current actor keeps its own mark: in target mode it uses the lighter accent so the
            // "this is the actor" bar and the "this row is choosable" bar never read as the same thing.
            int color = data.current() ? (targeting ? UiPalette.accentLight() : UiPalette.accent())
                    : UiPalette.accent();
            Draw.fill(graphics, box.x(), box.y(), width, box.h(), color);
        }
        int icon = Scale.effectivePx(Motion.ICON, scale);
        Rect portrait = Rect.of(box.x() + Scale.effectivePx(2, scale), box.y() + Scale.effectivePx(2, scale), icon,
                icon);
        Draw.fill(graphics, portrait, data.dead() ? Slate.LINE : Slate.SUNK);
        LivingEntity entity = living(data.entityId());
        if (entity != null) {
            Portraits.entity(graphics, entity, portrait.x(), portrait.y(), portrait.right(), portrait.bottom(),
                    portrait.x() + icon / 2.0F, portrait.y() + icon / 2.0F);
        }
        if (unavailable) Draw.fill(graphics, portrait, Motion.withAlpha(Slate.SUNK, 0.5D));
        int textColor = data.dead() || unavailable ? Slate.TEXT3 : Slate.TEXT;

        int nameX = box.x() + Scale.effectivePx(HudLayout.ROSTER_NAME_LEFT, scale);
        RosterRowText.Arrangement arrangement = arrange(minecraft(), data, layout.rosterNameBudget(),
                layout.rosterNameLineCap(), scale);
        ROW_TEXT.put(id, new String[]{data.name(), String.join(" ", arrangement.nameLines())});
        int lineHeight = Scale.effectivePx(Motion.LINE, scale);
        int textTop = box.y() + Scale.effectivePx(3, scale);
        int right = box.right() - Scale.effectivePx(2, scale);
        int healthWidth = textWidth(font, data.health());
        for (int line = 0; line < arrangement.nameLines().size(); line++) {
            Draw.scaledText(graphics, font, arrangement.nameLines().get(line), nameX, textTop + line * lineHeight, scale,
                    textColor);
        }
        int chip = arrangement.chip().isEmpty() ? 0
                : textWidth(font, arrangement.chip()) + Scale.effectivePx(Motion.CHIP_PAD_X, scale) * 2;
        int infoY = arrangement.infoMerged()
                ? textTop + (arrangement.nameLines().size() - 1) * lineHeight
                : textTop + arrangement.nameLines().size() * lineHeight;
        int chipX = arrangement.infoMerged()
                ? nameX + textWidth(font, arrangement.lastLine()) + Scale.effectivePx(RosterRowText.CHIP_GAP, scale)
                : nameX;
        if (chip > 0) {
            Rect chipBox = Rect.of(chipX, infoY, chip, Scale.effectivePx(Motion.CHIP_HEIGHT, scale));
            Draw.chip(graphics, chipBox, UiPalette.accentDark());
            Draw.scaledText(graphics, font, arrangement.chip(), chipBox.x() + Scale.effectivePx(Motion.CHIP_PAD_X, scale),
                    infoY + Scale.effectivePx(1, scale), scale, Slate.TEXT);
        }
        // Health is never cut: it is placed from the row's right edge, whatever the name did.
        Draw.scaledText(graphics, font, data.health(), right - healthWidth, infoY, scale,
                data.dead() || unavailable ? Slate.TEXT3 : Slate.TEXT2);

        int textLines = arrangement.textLines();
        int barY = box.y() + Scale.effectivePx(HudLayout.ROSTER_BAR_BASE, scale) + textLines * lineHeight;
        Rect bar = Rect.of(nameX, barY, Math.max(1, right - nameX),
                Scale.effectivePx(Motion.ROSTER_BAR_HEIGHT, scale));
        Draw.fill(graphics, bar, Slate.SUNK);
        if (!data.dead()) {
            int filled = (int) Math.round(bar.w() * data.fraction());
            if (filled > 0) Draw.fill(graphics, bar.x(), bar.y(), filled, bar.h(), data.ally() ? Slate.ALLY : Slate.ENEMY);
        }
    }

    private static void drawJournal(GuiGraphics graphics, long now, double delta) {
        Rect panel = layout.journalPanel();
        if (panel.w() <= 0) return;
        Font font = minecraft().font;
        double scale = layout.scale();
        HudModel.Journal journal = model.journal();
        // Exactly one base surface per frame, in every form: folded, the title strip; expanded, the one
        // continuous window from the title row's top edge to the footer's bottom edge. The tight and
        // narrow forms used to paint the strip and then a second surface under it, which the user saw
        // as two windows above 100%; the body of a folded form is opaque FLOAT because that window may
        // cover other tactical panels.
        Rect base = layout.journalBase();
        if (base.w() > 0) {
            Draw.shadow(graphics, base, Scale.PANEL_SHADOW, Slate.SHADOW, true);
            Draw.slab(graphics, base, layout.journalBodyFloats() ? Slate.FLOAT : Slate.SLAB);
        }
        Rect title = layout.journalTitle();
        Draw.scaledText(graphics, font, ClientText.get(ClientText.Keys.HUD_JOURNAL_TITLE), title.x(),
                baseline(title, scale), scale, Slate.TEXT);
        String toggleLabel = ClientText.get(JournalLabels.toggle(journal.bodyShown()));
        journalToggleTooltip = layout.journalToggleIconOnly() ? toggleLabel : null;
        if (layout.journalToggleIconOnly()) {
            buttonIcon(graphics, layout.journalToggle(),
                    journal.bodyShown() ? Draw.ICON_MINUS : Draw.ICON_PLUS, now, delta);
        } else {
            button(graphics, font, scale, layout.journalToggle(), toggleLabel, now, delta);
        }

        if (layout.journalMode() == HudLayout.JournalMode.HEADER) {
            // A folded journal still carries its grip: the hit area existed but the six dots were never
            // painted, so the corner could be dragged without anything visible to grab.
            journalHandlePainted = true;
            drawHandle(graphics, font, scale, HudLayout.JOURNAL, layout.journalHandle(), layout.journalHandleHit(),
                    layout.journalWidth(), null, now, delta);
            return;
        }
        Draw.accentRule(graphics, layout.journalRule());

        List<HudModel.Entry> entries = journal.entries();
        Rect body = layout.journalBody();
        // The layout owns the viewport: the entries that intersect the body, their draw rectangles and
        // the part of each one the body really shows. The scissor is what turns "does not fit" into a
        // partial entry the reader can scroll through, so nothing here cuts an entry with an ellipsis.
        List<HudLayout.JournalRow> rows = layout.journalRows();
        int textWidth = layout.journalTextWidth();
        graphics.enableScissor(body.x(), body.y(), body.right(), body.bottom());
        try {
            for (HudLayout.JournalRow row : rows) {
                if (row.index() < 0 || row.index() >= entries.size()) break;
                drawJournalEntry(graphics, font, scale, row.draw(), entries.get(row.index()), textWidth);
            }
        } finally {
            graphics.disableScissor();
        }
        journalHandlePainted = true;
        drawHandle(graphics, font, scale, HudLayout.JOURNAL, layout.journalHandle(), layout.journalHandleHit(),
                layout.journalWidth(), journalBodyDesignValue(), now, delta);
        // The bar's total is the whole stack in pixels and its visible part the body's own height, so
        // the thumb is the viewport's share of the content and its bottom end is the newest entry: one
        // coordinate contract shared by the painting, the wheel and the probe.
        drawScrollBar(graphics, body, layout.journalBar(), layout.journalTotalPx(), body.h(), journalThumbOffset());

        Draw.separator(graphics, layout.journalFooterRule());
        Rect status = layout.journalStatus();
        int statusY = baseline(status, scale);
        String count = journalCountText(entries);
        int countWidth = textWidth(font, count);
        Draw.scaledText(graphics, font, count, status.right() - countWidth, statusY, scale, Slate.TEXT3);
        int statusBudget = Math.max(1, status.w() - countWidth - Scale.effectivePx(4, scale));
        journalStatusFull = journal.status();
        String statusShown = ellipsis(font, journalStatusFull, statusBudget);
        journalStatusTruncated = !statusShown.equals(journalStatusFull);
        Draw.scaledText(graphics, font, statusShown, status.x(), statusY, scale, Slate.TEXT3);
    }

    /**
     * One entry, painted whole into its own rectangle. The scissor of the body cuts whatever falls
     * outside it, which is how an entry that does not fit at an edge is shown as far as it reaches -
     * the row's height is the full wrapped text, so no line is ever folded into an ellipsis.
     */
    private static void drawJournalEntry(GuiGraphics graphics, Font font, double scale, Rect row,
                                         HudModel.Entry entry, int textWidth) {
        int chipWidth = layout == null || layout.journalChipWidth() <= 0
                ? Scale.effectivePx(HudLayout.JOURNAL_CHIP_WIDTH, scale) : layout.journalChipWidth();
        int textLeft = row.x() + chipWidth + Scale.effectivePx(2, scale);
        int lineHeight = Scale.effectivePx(Motion.LINE, scale);
        List<String> lines = TextMetrics.wrap(entry.text(), Math.max(1, textWidth), scale, font::width);
        if (lines.isEmpty()) lines = List.of("");
        Rect chip = Rect.of(row.x(), row.y(), chipWidth, Scale.effectivePx(Motion.CHIP_HEIGHT, scale));
        Draw.chip(graphics, chip, Slate.RAISE);
        String label = entry.label();
        Draw.scaledText(graphics, font, ellipsis(font, label, chip.w() - Scale.effectivePx(2, scale) * 2),
                chip.x() + Scale.effectivePx(Motion.CHIP_PAD_X, scale), chip.y() + Scale.effectivePx(1, scale),
                scale, Slate.TEXT2);
        for (int index = 0; index < lines.size(); index++) {
            Draw.scaledText(graphics, font, lines.get(index), textLeft, row.y() + index * lineHeight, scale,
                    Slate.TEXT);
        }
    }

    /**
     * The footer count of the entries the body really drew: their own first and last sequence, and
     * the encounter's terminal sequence as the total it has always been. The viewport is the layout's,
     * so the count cannot claim entries that were clipped away.
     */
    private static String journalCountText(List<HudModel.Entry> entries) {
        List<HudLayout.JournalRow> rows = layout.journalRows();
        if (rows.isEmpty() || entries.isEmpty()) return BattleLog.countText(0L, 0L, true);
        int first = Math.min(rows.getFirst().index(), entries.size() - 1);
        int last = Math.min(rows.getLast().index(), entries.size() - 1);
        return BattleLog.countText(entries.get(first).sequence(), entries.get(last).sequence(), false);
    }

    /**
     * The thumb exactly as it is painted, so a driver can prove it never leaves the track - and that
     * its bottom end is the newest entry, which is the direction the user asked for.
     */
    private static Rect journalThumb() {
        Rect body = layout.journalBody();
        return ScrollList.thumb(layout.journalBar(), layout.journalTotalPx(), body.h(), journalThumbOffset(),
                layout.scale());
    }

    /** Where the journal's thumb sits from the track's top: the newest end puts it at the bottom. */
    private static int journalThumbOffset() {
        return JournalViewport.thumbOffset(layout.journalContent(), layout.journalView().scrollTop());
    }

    /**
     * The first or last sequence number the footer's count is built from: the entries the body really
     * drew, never the window the state happened to hold. -1 when nothing is drawn.
     */
    private static long journalCountSequence(boolean first) {
        List<HudModel.Entry> entries = model.journal().entries();
        List<HudLayout.JournalRow> rows = layout.journalRows();
        if (entries.isEmpty() || rows.isEmpty()) return -1;
        int index = first ? rows.getFirst().index() : rows.getLast().index();
        return entries.get(Math.max(0, Math.min(entries.size() - 1, index))).sequence();
    }

    private static void drawRail(GuiGraphics graphics, long now, double delta) {
        Rect rail = layout.rail();
        if (rail.w() <= 0) return;
        Font font = minecraft().font;
        double scale = layout.scale();
        Draw.fill(graphics, layout.railTrack(), Slate.LINE);
        Draw.fill(graphics, layout.railTick(), Slate.AP);
        List<HudLayout.Portrait> ordered = new ArrayList<>(layout.portraits());
        ordered.sort(java.util.Comparator.comparingInt(p -> p.current() ? Integer.MAX_VALUE : p.lane()));
        boolean targeting = entityTargeting();
        for (HudLayout.Portrait portrait : ordered) {
            String id = "rail-" + portrait.entityId();
            boolean hovered = id.equals(hoveredId);
            double progress = TWEEN.progress(id, hovered, now, Motion.HOVER_MS, delta);
            int[] health = BattleClientState.participantHealth.get(portrait.entityId());
            boolean dead = health != null && health[0] <= 0;
            boolean selectable = targeting
                    && actionFor(portrait.entityId()) != HudEntityTarget.Action.NONE;
            boolean unavailable = targeting && !selectable;
            int base = portrait.current() ? UiPalette.accent() : Draw.blend(Slate.LINE, UiPalette.accentDark(), progress);
            if (dead) base = Slate.SUNK;
            // A choosable portrait is outlined in the theme colour; the hovered one drops to the darker
            // accent. An unavailable one keeps its ordinary frame and dims only its own avatar.
            if (selectable && !portrait.current()) base = hovered ? UiPalette.accentDark() : UiPalette.accent();
            Draw.fill(graphics, portrait.box(), base);
            int inset = portrait.current() ? Scale.effectivePx(2, scale) : Scale.effectivePx(1, scale);
            Rect inner = portrait.box().inset(inset);
            if (inner.w() > 0 && inner.h() > 0) {
                Draw.fill(graphics, inner, Slate.SUNK);
                LivingEntity entity = living(portrait.entityId());
                if (entity != null) {
                    Portraits.entity(graphics, entity, inner.x(), inner.y(), inner.right(), inner.bottom(),
                            inner.x() + inner.w() / 2.0F, inner.y() + inner.h() / 2.0F);
                }
                if (unavailable) Draw.fill(graphics, inner, Motion.withAlpha(Slate.SUNK, 0.5D));
            }
            if (hovered && portrait.current()) {
                int width = Scale.rule(scale);
                Rect ring = portrait.box();
                Draw.fill(graphics, ring.x(), ring.y(), ring.w(), width, Slate.TEXT);
                Draw.fill(graphics, ring.x(), ring.bottom() - width, ring.w(), width, Slate.TEXT);
                Draw.fill(graphics, ring.x(), ring.y(), width, ring.h(), Slate.TEXT);
                Draw.fill(graphics, ring.right() - width, ring.y(), width, ring.h(), Slate.TEXT);
            }
        }
    }

    private static void drawHint(GuiGraphics graphics, long now) {
        Rect hint = layout.hint();
        if (hint.w() <= 0 || model.hintText() == null) return;
        Font font = minecraft().font;
        double scale = layout.scale();
        double fade = Motion.easeOutCubic(Motion.progress(now - hintSince, Motion.TOOLTIP_FADE_MS));
        Draw.shadow(graphics, hint, Scale.SMALL_SHADOW, Motion.withAlpha(Slate.SHADOW_SM, fade), false);
        Draw.chip(graphics, hint, Slate.FLOAT);
        int x = hint.x() + Scale.effectivePx(Tooltip.PAD_X, scale);
        int y = hint.y() + Scale.effectivePx(Tooltip.PAD_Y, scale);
        x = segment(graphics, font, scale, ClientText.get(ClientText.Keys.HUD_HINT_PREFIX), x, y, Slate.TEXT, fade,
                hint.right() - x);
        x = segment(graphics, font, scale, JournalLabels.HINT_KEY, x, y, Slate.AP, fade, hint.right() - x);
        segment(graphics, font, scale, ClientText.get(ClientText.Keys.HUD_HINT_SUFFIX), x, y, Slate.TEXT, fade,
                hint.right() - x);
    }

    private static int segment(GuiGraphics graphics, Font font, double scale, String text, int x, int y, int color,
                               double alpha, int budget) {        String shown = ellipsis(font, text, Math.max(1, budget));
        if (!shown.isEmpty()) {
            Draw.scaledText(graphics, font, shown, x, y, scale, Motion.withAlpha(color, alpha));
        }
        return x + textWidth(font, shown);
    }

    /**
     * One 2px bar for a scrollable list.
     *
     * @param total       pixels of content the bar reports
     * @param visible     pixels of it the body really shows
     * @param thumbOffset where the thumb sits, from the track's top - the caller decides which end of
     *                    its data the top means, so the roster passes its row offset and the journal
     *                    passes the distance from the oldest end ({@link JournalViewport#thumbOffset})
     */
    private static void drawScrollBar(GuiGraphics graphics, Rect area, Rect bar, int total, int visible,
                                      int thumbOffset) {
        Rect track = bar.w() > 0 ? bar : ScrollList.track(area, total, visible, layout.scale());
        if (track.w() <= 0) return;
        Draw.fill(graphics, track, Slate.SUNK);
        boolean hovered = hoveredId != null && area.contains(BattleUiRegions.cursorGui()[0],
                BattleUiRegions.cursorGui()[1]);
        Rect thumb = ScrollList.thumb(track, total, visible, thumbOffset, layout.scale());
        if (thumb.h() > 0) Draw.fill(graphics, thumb, hovered ? Slate.TEXT3 : Slate.LINE);
    }

    private static void button(GuiGraphics graphics, Font font, double scale, Rect rect, String label, long now,
                               double delta) {
        boolean pressed = buttonBase(graphics, rect, now, delta);
        String text = ellipsis(font, label, rect.w() - Scale.effectivePx(Motion.BUTTON_PAD_X, scale) * 2);
        int width = textWidth(font, text);
        Draw.scaledText(graphics, font, text, rect.x() + (rect.w() - width) / 2,
                baseline(rect, scale) + (pressed ? 1 : 0), scale, Slate.TEXT);
    }

    /**
     * The same button with a picture symbol instead of a glyph.
     *
     * <p>The journal's one-glyph toggle used to be a font character, so it sat on the text baseline instead of
     * in the middle of its button and its position moved with the language's font. It now uses the same
     * resource-drawn symbol the character page does: the shape is centred by whole pixels and no font metric is
     * consulted for it.</p>
     */
    private static void buttonIcon(GuiGraphics graphics, Rect rect, Draw.Sprite symbol, long now, double delta) {
        boolean pressed = buttonBase(graphics, rect, now, delta);
        Rect box = pressed ? rect.move(0, 1) : rect;
        Draw.sprite(graphics, symbol, box, Slate.TEXT);
    }

    /** The shared surface of the journal toggle, in whichever state the pointer put it. */
    private static boolean buttonBase(GuiGraphics graphics, Rect rect, long now, double delta) {
        boolean hovered = HudLayout.JOURNAL_TOGGLE.equals(hoveredId);
        boolean pressed = hovered && TWEEN.pressActive(HudLayout.JOURNAL_TOGGLE, now);
        double progress = TWEEN.progress(HudLayout.JOURNAL_TOGGLE, hovered, now, Motion.HOVER_MS, delta);
        int base = pressed ? Slate.SUNK : Draw.blend(Slate.RAISE, Slate.HOVER, progress);
        Draw.chip(graphics, rect, base);
        return pressed;
    }

    /**
     * Tooltip for the one control under the pointer that had to cut its text: a roster name, the
     * roster's weapon line or a journal entry. It appears after the hover delay, never under the
     * pointer, and is a floating opaque surface.
     */
    private static void drawTooltip(GuiGraphics graphics, long now) {
        tooltipBox = Rect.EMPTY;
        tooltipLines = List.of();
        // The drag tag is the only hint while a size drag runs; the cursor area stays clear.
        if (dragging() || hoveredId == null || layout == null) return;
        TWEEN.hovered(hoveredId, lastFrame);
        if (!TWEEN.tooltipDue(now)) return;
        String full = null;
        Rect trigger = Rect.EMPTY;
        if (hoveredId.startsWith("roster-")) {
            String[] recorded = ROW_TEXT.get(hoveredId);
            // A row that cannot be chosen states why, even when its name was not cut.
            if (!hoverBlocked.isEmpty()) {
                full = hoverBlocked;
            } else if (recorded == null || recorded[0].equals(recorded[1])) {
                return;
            } else {
                full = recorded[0];
            }
            int id;
            try {
                id = Integer.parseInt(hoveredId.substring("roster-".length()));
            } catch (NumberFormatException ignored) {
                return;
            }
            for (int slot = 0; slot < layout.rosterRows(); slot++) {
                int index = rosterScroll + slot;
                if (index < model.roster().rows().size()
                        && model.roster().rows().get(index).entityId() == id) {
                    trigger = layout.rosterRow(slot);
                    break;
                }
            }
        } else if (hoveredId.startsWith("rail-")) {
            if (hoverBlocked.isEmpty()) return;
            full = hoverBlocked;
            int id;
            try {
                id = Integer.parseInt(hoveredId.substring("rail-".length()));
            } catch (NumberFormatException ignored) {
                return;
            }
            for (HudLayout.Portrait portrait : layout.portraits()) {
                if (portrait.entityId() == id) {
                    trigger = portrait.box();
                    break;
                }
            }
        } else if (HudLayout.JOURNAL_TOGGLE.equals(hoveredId)) {
            // The icon button only shows "−"/"+", so its whole wording hangs on the tooltip.
            if (journalToggleTooltip == null) return;
            full = journalToggleTooltip;
            trigger = layout.journalToggle();
        } else if (HudLayout.ROSTER_FOOTER_AP.equals(hoveredId)) {
            if (!recoveryTruncated) return;
            full = recoveryFull;
            trigger = layout.rosterFooterAp();
        } else if (HudLayout.JOURNAL_STATUS.equals(hoveredId)) {
            if (!journalStatusTruncated) return;
            full = journalStatusFull;
            trigger = layout.journalStatus();
        } else if (HudLayout.ROSTER_FOOTER_WEAPON.equals(hoveredId)) {
            if (!weaponTruncated) return;
            full = weaponFull;
            trigger = layout.rosterFooterWeapon();
        } else {
            return;
        }
        if (full == null || full.isEmpty() || trigger.w() <= 0) return;
        Font font = minecraft().font;
        double scale = layout.scale();
        Tooltip.Content content = Tooltip.content(full, null, scale, font::width);
        if (content.isEmpty()) return;
        int boxWidth = Tooltip.width(content, scale, font::width);
        int boxHeight = Tooltip.height(content, scale);
        double[] cursor = BattleUiRegions.cursorGui();
        Rect box = Tooltip.place(trigger, boxWidth, boxHeight, minecraft().getWindow().getGuiScaledWidth(),
                minecraft().getWindow().getGuiScaledHeight(), cursor[0], cursor[1],
                Scale.effectivePx(Tooltip.GAP, scale));
        double fade = Motion.easeOutCubic(Motion.progress(now - TWEEN.hoveredSince() - Motion.TOOLTIP_DELAY_MS,
                Motion.TOOLTIP_FADE_MS));
        Draw.shadow(graphics, box, Scale.SMALL_SHADOW, Motion.withAlpha(Slate.SHADOW_SM, fade), false);
        Draw.chip(graphics, box, Slate.FLOAT);
        int lineY = Tooltip.textTop(box, scale);
        List<String> drawn = new ArrayList<>();
        for (String line : content.primary()) {
            Draw.scaledText(graphics, font, line, Tooltip.textLeft(box, scale), lineY, scale,
                    Motion.withAlpha(Slate.TEXT, fade));
            drawn.add(line);
            lineY += Scale.effectivePx(Motion.LINE, scale);
        }
        tooltipBox = box;
        tooltipLines = List.copyOf(drawn);
    }

    // --------------------------------------------------------------- size handles

    /** The roster's current height as the 100% value the tag shows, or null while it is automatic. */
    private static Integer rosterHeightValue() {
        if (layout == null || layout.rosterPanel().h() <= 0) return null;
        Integer saved = UiZoom.rosterHeight();
        if (saved == null) return Math.round(layout.rosterPanel().h() / (float) layout.scale());
        return saved;
    }

    private static Integer journalBodyDesignValue() {
        if (layout == null || layout.journalBody().h() <= 0) return null;
        Integer saved = UiZoom.journalHeight();
        return saved == null ? Math.round(layout.journalBody().h() / (float) layout.scale()) : saved;
    }

    /**
     * The 7×7 dot grip in the panel's bottom-right corner, its hover and drag states, the drag outline
     * and the size tag. The dots, the reserved corner and the hit area are fixed GUI pixels: they do
     * not follow the type-size preset, so the grip stays grabbable at every preset.
     */
    private static void drawHandle(GuiGraphics graphics, Font font, double scale, String id, Rect visual, Rect hit,
                                   int widthPx, Integer heightPx, long now, double delta) {
        if (visual.w() <= 0) return;
        boolean active = dragging() && id.startsWith(drag.target() == HudDrag.Target.ROSTER ? "roster" : "journal");
        boolean hovered = hoveredId != null && hoveredId.equals(id);
        double progress = TWEEN.progress(id, hovered || active, now, Motion.HOVER_MS, delta);
        int dot = active ? UiPalette.accentLight() : Draw.blend(Slate.LINE, Slate.TEXT3, progress);
        int[][] dots = {{1, 1}, {3, 1}, {5, 1}, {1, 3}, {3, 3}, {1, 5}};
        for (int[] offset : dots) {
            Draw.fill(graphics, visual.right() - offset[0], visual.bottom() - offset[1], 1, 1, dot);
        }
        if (!active) return;
        // While dragging: a 1px accent outline just outside the panel edge, and the size tag.
        Rect panel = id.startsWith("roster") ? layout.rosterPanel() : layout.journalPanel();
        if (panel.w() > 0) {
            Draw.fill(graphics, panel.x() - 1, panel.y() - 1, panel.w() + 2, 1, UiPalette.accent());
            Draw.fill(graphics, panel.x() - 1, panel.bottom(), panel.w() + 2, 1, UiPalette.accent());
            Draw.fill(graphics, panel.x() - 1, panel.y(), 1, panel.h(), UiPalette.accent());
            Draw.fill(graphics, panel.right(), panel.y(), 1, panel.h(), UiPalette.accent());
        }
        // The size tag is not drawn here: this runs before the footer lines are painted, so a tag drawn
        // now would be buried under - or worse, would bury - the numbers. render() draws it last, on a
        // position chosen to cover no number (see drawDragTag).
    }

    /**
     * The size tag of a running drag, drawn after the whole HUD so it is never hidden, at a position
     * that covers no number: the footer's AP and weapon lines, the journal's count, and every row text
     * are protected. The preferred spot is above the handle; when that would cover a number the tag
     * flips out of the panel instead (see {@link DragTag}).
     */
    private static void drawDragTag(GuiGraphics graphics) {
        dragTag = Rect.EMPTY;
        dragTagWhere = "none";
        if (!dragging() || layout == null || hidden(minecraft())) return;
        boolean journal = drag.target() == HudDrag.Target.JOURNAL;
        Rect panel = journal ? layout.journalPanel() : layout.rosterPanel();
        Rect handle = journal ? layout.journalHandle() : layout.rosterHandle();
        if (panel.w() <= 0 || handle.w() <= 0) return;
        Font font = minecraft().font;
        double scale = layout.scale();
        int widthPx = journal ? layout.journalWidth() : layout.rosterWidth();
        Integer heightPx = journal ? journalBodyDesignValue() : rosterHeightValue();
        boolean showHeight = !journal || layout.journalMode() != HudLayout.JournalMode.HEADER;
        String text = showHeight && heightPx != null
                ? designWidth(widthPx, scale) + " × " + heightPx
                : Integer.toString(designWidth(widthPx, scale));
        int pad = Scale.effectivePx(Tooltip.PAD_X, scale);
        int tagWidth = textWidth(font, text) + pad * 2;
        int tagHeight = Scale.effectivePx(Tooltip.PAD_Y * 2 + Motion.LINE, scale);
        DragTag.Placement placement = DragTag.place(handle, panel, tagWidth, tagHeight, layout.numberRects(),
                layout.rail(), minecraft().getWindow().getGuiScaledWidth(),
                minecraft().getWindow().getGuiScaledHeight());
        Rect tag = placement.rect();
        boolean clamped = clampColour(journal ? HudLayout.JOURNAL : HudLayout.ROSTER) == Slate.TEXT2;
        Draw.shadow(graphics, tag, Scale.SMALL_SHADOW, Slate.SHADOW_SM, false);
        Draw.chip(graphics, tag, Slate.FLOAT);
        Draw.scaledText(graphics, font, text, tag.x() + pad, tag.y() + Scale.effectivePx(Tooltip.PAD_Y, scale),
                scale, clamped ? Slate.TEXT2 : Slate.TEXT);
        dragTag = tag;
        dragTagWhere = placement.where().name().toLowerCase(Locale.ROOT);
    }

    /** The 100% value a pixel size corresponds to, which is what the tag and the defaults compare. */
    private static int designWidth(int px, double scale) {
        return Math.round(px / (float) Math.max(0.01D, scale));
    }

    /**
     * The tag's number turns TEXT2 when the frame could not honour it: the layout clamped the panel
     * below what the pointer asked for. Comparing against the layout is what makes the highlight mean
     * "you are at a limit", not merely "the pointer moved".
     */
    private static int clampColour(String id) {
        if (drag == null || !drag.active()) return Slate.TEXT;
        boolean journal = drag.target() == HudDrag.Target.JOURNAL;
        Integer asked = journal ? UiZoom.journalWidth() : UiZoom.rosterWidth();
        if (asked == null) return Slate.TEXT;
        int actual = journal ? layout.journalWidth() : layout.rosterWidth();
        int wanted = Scale.effectivePx(asked, layout.scale());
        return actual < wanted ? Slate.TEXT2 : Slate.TEXT;
    }

    // --------------------------------------------------------------- probe

    private static String probeJson() {
        StringBuilder nodes = new StringBuilder();
        boolean first = true;
        String[][] fixed = {
                {HudLayout.ROSTER, null}, {HudLayout.ROSTER_TITLE, null}, {HudLayout.JOURNAL, null},
                {HudLayout.JOURNAL_TOGGLE, null}, {HudLayout.JOURNAL_BODY, null}, {HudLayout.RAIL, null},
                {HudLayout.HINT, null}, {HANDLE_ROSTER, null}, {HANDLE_JOURNAL, null},
        };
        for (String[] entry : fixed) {
            Rect rect = switch (entry[0]) {
                case HudLayout.ROSTER -> layout.rosterPanel();
                case HudLayout.ROSTER_TITLE -> layout.rosterTitle();
                case HudLayout.JOURNAL -> layout.journalPanel();
                case HudLayout.JOURNAL_TOGGLE -> layout.journalToggle();
                case HudLayout.JOURNAL_BODY -> layout.journalBody();
                case HudLayout.RAIL -> layout.rail();
                // The two size handles are reported as their 12x12 hit areas: that is the rectangle a
                // driver has to press, and it is what "the grip is where the click lands" means.
                case HANDLE_ROSTER -> layout.rosterHandleHit();
                case HANDLE_JOURNAL -> layout.journalHandleHit();
                default -> layout.hint();
            };
            first = appendNode(nodes, first, entry[0], rect, true);
        }
        for (int slot = 0; slot < layout.rosterRows(); slot++) {
            int index = rosterScroll + slot;
            if (index >= model.roster().rows().size()) break;
            HudModel.RosterRow row = model.roster().rows().get(index);
            first = appendNode(nodes, first, "roster-hit-" + row.entityId(), layout.rosterRow(slot), true,
                    String.format(Locale.ROOT, ",\"name\":\"%s\",\"lines\":%d", escape(row.name()),
                            layout.rosterLines(slot)));
        }
        for (HudLayout.Portrait portrait : layout.portraits()) {
            first = appendNode(nodes, first, "rail-portrait-" + portrait.entityId(), portrait.box(), true);
        }
        for (int index = 0; index < layout.journalRows().size(); index++) {
            HudLayout.JournalRow row = layout.journalRows().get(index);
            first = appendNode(nodes, first, "journal-entry-" + index, row.visible(), false);
        }
        return String.format(Locale.ROOT,
                "{\"clientMirror\":true,\"form\":\"%s\",\"scale\":%.3f,\"bres\":%d,\"bflo\":%d,"
                        + "\"journalMode\":\"%s\",\"rosterRows\":%d,\"rosterScroll\":%d,"
                        + "\"rosterNameLineCap\":%d,\"lineHeight\":%d,\"rowBase\":%d,"
                        // The journal reports its scroll position in GUI pixels: offsetPx is the
                        // distance from the newest end (0 = following it), totalPx is the whole stack
                        // and maxOffsetPx the furthest the body can browse. The old entry-count
                        // "offset" is gone, so a script can never read pixels as a number of entries.
                        + "\"journal\":{\"offsetUnit\":\"px\",\"offsetPx\":%d,\"totalPx\":%d,\"maxOffsetPx\":%d,"
                        + "\"scrollTop\":%d,\"anchorSequence\":%d,\"browsing\":%b,"
                        + "\"total\":%d,\"drawn\":%d,\"first\":%d,"
                        + "\"bar\":{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d},"
                        + "\"thumb\":{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d},"
                        + "\"countFirst\":%d,\"countLast\":%d,\"rows\":[%s],\"texts\":[%s]},"
                        + "\"sizes\":{\"rosterWidth\":%d,\"rosterWidthDesign\":%d,\"rosterHeight\":%d,"
                        + "\"rosterAuto\":%b,\"journalWidth\":%d,\"journalBody\":%d,\"journalBodyDesign\":%d},"
                        + "\"drag\":\"%s\",\"dragAxes\":{\"width\":%b,\"height\":%b},"
                        + "\"dragTag\":{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d},"
                        + "\"dragTagWhere\":\"%s\",\"protected\":%s,\"journalHandleDrawn\":%b,"
                        + "\"palette\":{\"theme\":\"%s\",\"range\":\"%s\"},"
                        + "\"resourceGeneration\":%d,"
                        + "\"weapon\":{\"text\":\"%s\",\"shown\":\"%s\",\"truncated\":%b},"
                        + "\"hovered\":\"%s\",\"tooltip\":{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d},"
                        + "\"target\":{\"mode\":\"%s\",\"aim\":\"%s\",\"active\":%b,"
                        + "\"hoverAction\":\"%s\",\"hoverBlocked\":\"%s\",\"capture\":\"%s\"},"
                        + "\"alpha\":{\"hud\":%.3f},\"nodes\":[%s]}",
                layout.input().form(), layout.scale(), layout.bres(), layout.bflo(), layout.journalMode(),
                layout.rosterRows(), rosterScroll, layout.rosterNameLineCap(),
                Scale.effectivePx(Motion.LINE, layout.scale()), Scale.effectivePx(HudLayout.ROSTER_ROW_BASE, layout.scale()),
                layout.journalOffsetPx(), layout.journalTotalPx(), layout.journalMaxScrollPx(),
                layout.journalView().scrollTop(), layout.journalAnchor().sequence(),
                !layout.journalAnchor().newest(),
                layout.journalTotal(), layout.journalDrawn(), layout.journalFirstIndex(),
                layout.journalBar().x(), layout.journalBar().y(), layout.journalBar().w(), layout.journalBar().h(),
                journalThumb().x(), journalThumb().y(), journalThumb().w(), journalThumb().h(),
                journalCountSequence(true), journalCountSequence(false), journalRows(), journalTexts(),
                layout.rosterWidth(), designWidth(layout.rosterWidth(), layout.scale()), layout.rosterPanel().h(),
                UiZoom.rosterHeight() == null, layout.journalWidth(), layout.journalBody().h(),
                journalBodyDesignValue() == null ? -1 : journalBodyDesignValue(),
                drag == null || !drag.active() ? "none" : drag.target().name().toLowerCase(Locale.ROOT),
                drag != null && drag.movedWidth(), drag != null && drag.movedHeight(),
                dragTag.x(), dragTag.y(), dragTag.w(), dragTag.h(), dragTagWhere, protectedRectsJson(),
                journalHandlePainted,
                Palette.hex(UiPalette.theme()), Palette.hex(UiPalette.range()),
                ClientResourceGeneration.current(),
                escape(weaponFull), escape(weaponShown), weaponTruncated,
                hoveredId == null ? "" : hoveredId, tooltipBox.x(), tooltipBox.y(), tooltipBox.w(), tooltipBox.h(),
                BattleClientState.mode == null ? "NONE" : BattleClientState.mode.name(),
                skillAim() == null ? "" : skillAim().name(),
                entityTargeting(), hoverAction.name(), escape(hoverBlocked),
                pendingTarget == null ? "" : pendingTarget.id() + ":" + pendingTarget.action().name(),
                lastAlpha, nodes);
    }

    /**
     * The journal entries actually drawn, oldest first, each with its row, the row's height and the
     * line count the layout reserved for it. Development only: a driver has to be able to check that a
     * row is as tall as the entry painted into it - the mixed single/double-line case - instead of
     * guessing from pixels.
     */
    /** The rectangles that carry numbers or text, so a tag that covers one is visible to a driver. */
    private static String protectedRectsJson() {
        if (layout == null) return "[]";
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (Rect rect : layout.numberRects()) {
            if (!first) builder.append(',');
            first = false;
            builder.append(String.format(Locale.ROOT, "{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d}",
                    rect.x(), rect.y(), rect.w(), rect.h()));
        }
        return builder.append(']').toString();
    }

    /**
     * The retained journal text as this client renders it, last entries first. Development only.
     *
     * <p>This is a <em>client-language mirror</em>, not the wire content: the journal keeps the server's
     * component structure and resolves it at the display point, so what a driver reads here is this
     * client's own language for the same rows another client may see in a different language. It is
     * therefore evidence about rendering, never evidence about what the server authored.</p>
     */
    private static String journalTexts() {
        List<HudModel.Entry> entries = model.journal().entries();
        if (entries.isEmpty()) return "";
        StringBuilder report = new StringBuilder();
        int from = Math.max(0, entries.size() - 30);
        for (int index = from; index < entries.size(); index++) {
            if (index > from) report.append(',');
            HudModel.Entry entry = entries.get(index);
            report.append("{\"sequence\":").append(entry.sequence())
                    .append(",\"label\":\"").append(escape(entry.label()))
                    .append("\",\"text\":\"").append(escape(entry.text())).append("\"}");
        }
        return report.toString();
    }

    /**
     * The journal entries this frame really draws, oldest first: each one's own sequence, the
     * rectangle its text is painted into, the part of it inside the body, whether the scissor cuts it
     * at an edge and the line count the layout measured for it. Development only - a driver has to be
     * able to tell a partial entry from a whole one instead of guessing from pixels.
     */
    private static String journalRows() {
        if (layout == null) return "";
        List<HudModel.Entry> entries = model.journal().entries();
        List<HudLayout.JournalRow> rows = layout.journalRows();
        if (rows.isEmpty()) return "";
        StringBuilder report = new StringBuilder();
        for (int index = 0; index < rows.size(); index++) {
            HudLayout.JournalRow row = rows.get(index);
            if (row.index() < 0 || row.index() >= entries.size()) break;
            if (index > 0) report.append(',');
            report.append("{\"sequence\":").append(entries.get(row.index()).sequence())
                    .append(",\"y\":").append(row.draw().y()).append(",\"h\":").append(row.draw().h())
                    .append(",\"visibleY\":").append(row.visible().y())
                    .append(",\"visibleH\":").append(row.visible().h())
                    .append(",\"clippedTop\":").append(row.clippedTop())
                    .append(",\"clippedBottom\":").append(row.clippedBottom())
                    .append(",\"lines\":").append(row.lines()).append('}');
        }
        return report.toString();
    }

    /**
     * A frame that draws no HUD still reports that it is hidden - and the client's resource
     * generation, which is what a panel layout is keyed on. Development only.
     */
    static String hiddenJson() {
        return "{\"nodes\":[],\"hidden\":true,\"resourceGeneration\":" + ClientResourceGeneration.current() + "}";
    }

    private static boolean appendNode(StringBuilder nodes, boolean first, String id, Rect rect, boolean interactive) {
        return appendNode(nodes, first, id, rect, interactive, "");
    }

    /** One node, with extra fields the caller already formatted (a roster row's own name and lines). */
    private static boolean appendNode(StringBuilder nodes, boolean first, String id, Rect rect, boolean interactive,
                                      String extra) {
        if (rect == null || rect.w() <= 0 || rect.h() <= 0) return first;
        if (!first) nodes.append(',');
        nodes.append("{\"id\":\"").append(id).append("\",\"x\":").append(rect.x()).append(",\"y\":").append(rect.y())
                .append(",\"w\":").append(rect.w()).append(",\"h\":").append(rect.h()).append(extra)
                .append(",\"interactive\":").append(interactive)
                .append(",\"hovered\":").append(id.equals(hoveredId)).append('}');
        return false;
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    // --------------------------------------------------------------- helpers

    private static LivingEntity living(int entityId) {
        Minecraft minecraft = minecraft();
        return minecraft.level != null && minecraft.level.getEntity(entityId) instanceof LivingEntity living
                ? living : null;
    }

    private static int textWidth(Font font, String text) {
        return TextMetrics.width(font.width(text == null ? "" : text), layout == null ? 1.0D : layout.scale());
    }

    private static String ellipsis(Font font, String text, int width) {
        return TextMetrics.ellipsis(text, width, layout == null ? 1.0D : layout.scale(), font::width);
    }

    private static int baseline(Rect box, double textScale) {
        int line = (int) Math.round(Motion.LINE * textScale);
        return box.y() + Math.max(0, (box.h() - line) / 2);
    }

    /** Fades the hint in when it appears and drops it immediately when it goes. */
    public static void hintAppeared() {
        hintSince = Util.getMillis();
    }

    /** The two exclusive paint hooks: GUI layer without a screen, screen render with a tactical one. */
    @EventBusSubscriber(modid = Tactical.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() {
        }

        @SubscribeEvent
        public static void onGuiPost(RenderGuiEvent.Post event) {
            Minecraft minecraft = Minecraft.getInstance();
            // A screen owns the frame: the screen hook paints the HUD there, and painting it here as
            // well would draw the same panels twice in one frame.
            if (minecraft.screen != null) return;
            if (minecraft.options.hideGui) {
                publishHidden();
                return;
            }
            if (model.hintText() == null && !BattleClientState.active) {
                publishHidden();
                return;
            }
            render(event.getGuiGraphics());
        }

        @SubscribeEvent
        public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.options.hideGui) {
                publishHidden();
                return;
            }
            // A chat screen gets the left column painted over its messages - passively, because the
            // input handlers reject every pointer, wheel and key while it is open. Any other screen
            // keeps hiding the HUD exactly as before.
            if (!(minecraft.screen instanceof TacticalSurface) && !chatOpen(minecraft)) {
                publishHidden();
                return;
            }
            render(event.getGuiGraphics());
            // The screen's own content was drawn before this hook, so a modal popover opened by that
            // screen would end up under the roster and the charge rail. Its owner paints it here, on
            // top of the HUD, once per frame.
            if (minecraft.screen instanceof TacticalSurface surface) {
                event.getGuiGraphics().flush();
                surface.renderTopOverlay(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(),
                        event.getPartialTick());
                event.getGuiGraphics().flush();
            }
        }

        /** A frame that draws no HUD must not leave the previous frame's rectangles in the probe. */
        private static void publishHidden() {
            if (DevProbe.enabled()) DevProbe.publishHud(hiddenJson());
        }

        @SubscribeEvent
        public static void onScreenClosed(ScreenEvent.Closing event) {
            clearHover();
        }
    }
}
