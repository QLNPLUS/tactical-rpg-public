package com.tactical.neoforge.client.ui.character;

import com.tactical.core.progression.CharacterStat;
import com.tactical.core.progression.ProgressionReason;
import com.tactical.neoforge.client.UiPalette;
import com.tactical.neoforge.client.UiZoom;
import com.tactical.neoforge.client.progression.CharacterClientState;
import com.tactical.neoforge.client.ui.layout.Box;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.ScrollList;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.panel.TooltipLayer;
import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.neoforge.client.ui.platform.Draw;
import com.tactical.neoforge.client.ui.screen.NativeTacticalScreen;
import com.tactical.neoforge.client.ui.screen.TacticalSurface;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Slate;
import com.tactical.neoforge.client.ui.widget.Tooltip;
import com.tactical.neoforge.network.CharacterStatePacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The character page: a fully modal native screen over the battlefield.
 *
 * <p>It draws only what the server sent - level, price, points, coefficients and the six derived values - and
 * it never prices anything itself. The local draft is the one thing the player owns: {@code +}/{@code −}
 * change it, the server previews it, and one confirmation commits it. Levelling up is a different
 * transaction with its own confirmation layer, so the two can never be confused.</p>
 *
 * <p>While it is open it owns every key, every wheel notch and every pointer gesture, inside the page and
 * outside it: nothing may reach the hotbar, the battle page, the HUD or the camera behind it. That ownership
 * is decided by {@link CharacterModalPolicy} and enforced here plus in the battle input chain.</p>
 */
public final class CharacterScreen extends NativeTacticalScreen {

    public static final String SCREEN_ID = "character";

    private static CharacterScreen open;

    private final CharacterPanelModel model;
    private final CharacterAnswerBridge bridge = new CharacterAnswerBridge();
    private final com.tactical.neoforge.client.ui.widget.PointerCapture capture =
            new com.tactical.neoforge.client.ui.widget.PointerCapture();
    /** The cache the bridge reads; a field so the page never reaches a client singleton per frame. */
    private static final CharacterAnswerBridge.Source CACHE = new CharacterAnswerBridge.Source() {
        @Override public CharacterStatePacket current() { return CharacterClientState.current(); }

        @Override public boolean needsRefresh() { return CharacterClientState.needsRefresh(); }

        @Override public CharacterStatePacket refreshNotice() { return CharacterClientState.refreshNotice(); }

        @Override public void clearRefreshDemand() { CharacterClientState.clearRefreshDemand(); }
    };
    private String battleSignature;
    private double contentAlpha = 1.0D;
    private CharacterLayout layout;
    private CharacterLayout.Content content;
    private int scroll;
    private String focus;
    private String hovered;
    private String tipPrimary = "";
    private String tipSecondary = "";
    private long tipSince;
    private int tipX;
    private int tipY;
    private long openedAt;

    private CharacterScreen() {
        // Request ids come from the shared client counter, so closing and reopening never reuses a smaller id.
        this.model = new CharacterPanelModel(new LiveActions(), CharacterClientState::beginRequest);
        this.openedAt = Util.getMillis();
        CharacterStatePacket cached = CharacterClientState.current();
        // The cached answer is shown (so the page is not blank for one round trip) but it is not an answer to
        // the request this page is about to send: it belongs to an earlier visit.
        if (model.seed(cached)) bridge.markSeen(cached);
        battleSignature = battleSignature();
        // A page opened locally asks without the open intent; "open" belongs to the server-driven entry only.
        model.requestSnapshot(false);
        cancelForeignGestures();
    }

    /** Opening the page cancels any HUD size drag or camera gesture that was in flight behind it. */
    private static void cancelForeignGestures() {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft == null) return;
        com.tactical.neoforge.client.ui.hud.NativeHud.cancelDrag(minecraft);
        com.tactical.neoforge.client.DungeonsInputBridge.cancelGesture();
    }

    /** The battle membership this page was last synced with; a change asks for one fresh snapshot. */
    private static String battleSignature() {
        var state = com.tactical.neoforge.client.BattleClientState.active;
        var session = com.tactical.neoforge.client.BattleClientState.sessionId;
        // Whether points may be spent in a battle is "my own turn with nothing in flight", which changes
        // without the membership changing at all: the phase, my action right and the two busy flags are part
        // of the signature so an open page re-asks the server the moment it could become editable or read-only.
        return state + "|" + (session == null ? "" : session.toString())
                + "|" + com.tactical.neoforge.client.BattleClientState.phase
                + "|" + com.tactical.neoforge.client.BattleClientState.isPlayerTurn()
                + "|" + com.tactical.neoforge.client.BattleClientState.moving
                + "|" + com.tactical.neoforge.client.BattleClientState.terrainFalling;
    }

    /**
     * Opens the page for the local player and asks for a fresh snapshot.
     *
     * <p>Opening always re-requests: the experience balance and the battle state of an earlier visit are never
     * reused, and an answer that arrives later is the only thing that can fill the page.</p>
     */
    public static void open() {
        CharacterScreen screen = new CharacterScreen();
        open = screen;
        net.minecraft.client.Minecraft.getInstance().setScreen(screen);
    }

    /** The open page, or null. */
    public static CharacterScreen current() { return open; }

    /**
     * True while this page really is the open screen.
     *
     * <p>It is asked by the battle input chain before its hotbar, HUD and camera branches, so a page that was
     * replaced by another screen - or removed by the game - must stop owning input immediately instead of
     * becoming a ghost that blocks every other entry.</p>
     */
    public static boolean isOpen() {
        CharacterScreen screen = open;
        if (screen == null) return false;
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        return minecraft != null && minecraft.screen == screen;
    }

    /** The game removed the page (another screen, a disconnect, the world changing). */
    @Override public void removed() {
        if (open == this) open = null;
        super.removed();
    }

    /**
     * Hands whatever is new in the client cache to the open page.
     *
     * <p>The network handler only knows {@code CharacterClientState}; this bridge is what turns that cache into
     * the page, including the answer that carries no numbers (which must release the wait) and two consecutive
     * server pushes that share request {@code 0}. While the page is open it also watches this client's own
     * membership: entering or leaving a battle asks for exactly one fresh snapshot, so the page never keeps
     * showing a "can change" state the server has already refused.</p>
     */
    public static void pump() {
        CharacterScreen screen = open;
        if (screen == null) return;
        boolean changed = screen.bridge.deliver(screen.model, CACHE);
        String signature = battleSignature();
        if (!signature.equals(screen.battleSignature)) {
            screen.battleSignature = signature;
            // One merged demand, not one packet per frame: a change of membership, of my own action right or
            // of a busy flag makes the page ask again, and a demand that lands on top of a running transaction
            // waits for that transaction's own answer (which already carries fresh permissions).
            screen.model.markPermissionStale();
            changed = true;
        }
        if (screen.model.ensurePermissionRefresh()) changed = true;
        // A refresh is not a new layout: the panel is only rebuilt when something the geometry depends on
        // really changed, so an answer never re-centres the page, moves a button or replays the entrance.
        if (changed) screen.ensureLayout();
    }

    /** Closes the page because the connection or the world changed; it never sends anything. */
    public static void closeForLifecycle() {
        CharacterScreen screen = open;
        open = null;
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft != null && minecraft.screen == screen) minecraft.setScreen(null);
    }

    /** The request id of the newest preview this page asked for, so its answer is not mistaken for a commit. */
    private int lastPreviewRequestHint = -1;

    @Override public String screenId() { return SCREEN_ID; }

    @Override protected void rebuild() {
        double scale = Scale.effective(UiZoom.zoom());
        List<String> labels = List.of(text(Keys.CONFIRM), text(Keys.DISCARD), text(Keys.LEVEL_UP));
        List<String> popupLabels = List.of(text(Keys.POPUP_CONFIRM), text(Keys.POPUP_CANCEL));
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        content = content();
        // The layout never lowers the preset the player chose: a short window scrolls the body instead.
        layout = CharacterLayout.build(width, height, scale, content, font::width, labels, popupLabels, scroll);
        scroll = layout.scrollOffset();
    }

    /**
     * What the geometry depends on, taken from the model's own answers.
     *
     * <p>The three action flags are not "the state exists": they are the same predicates the handlers use, so
     * a button is pressable exactly when pressing it would do something. The page then has nothing to explain
     * away with a silent no-op.</p>
     */
    private CharacterLayout.Content content() {
        // The two per-stat masks are the model's own answers, so the boxes the pointer can press are the same
        // decision as the buttons the page draws: no point left, a stat at its cap, or nothing drafted means the
        // control is inert here rather than silently ignored in a handler after the press already started.
        boolean[] addable = new boolean[CharacterStat.COUNT];
        boolean[] removable = new boolean[CharacterStat.COUNT];
        for (CharacterStat stat : CharacterStat.values()) {
            addable[stat.index()] = model.canAdd(stat);
            removable[stat.index()] = model.canRemove(stat);
        }
        return new CharacterLayout.Content(
                model.state() != null && model.state().profileValid(), model.readOnly() != null, model.waiting(),
                model.upgradePopupOpen(), model.refreshNeeded(),
                model.canUpgrade(), model.canConfirmAllocation(), !model.draft().isEmpty(),
                CharacterLayout.Content.mask(addable), CharacterLayout.Content.mask(removable));
    }

    /** Rebuilds only when something the layout depends on changed; the page is not rebuilt per frame. */
    private void ensureLayout() {
        double scale = Scale.effective(UiZoom.zoom());
        CharacterLayout.Content wanted = content();
        if (layout == null || layout.guiWidth() != width || layout.guiHeight() != height
                || Math.abs(layout.scale() - scale) > 1.0E-6D || !wanted.equals(content)) {
            rebuild();
        }
    }

    // ---- drawing ----------------------------------------------------------------------------------

    /**
     * The page is drawn in the pass that really ends up above the battle HUD.
     *
     * <p>The HUD paints from {@code ScreenEvent.Render.Post}, i.e. after a screen's own content, so a
     * centred modal page drawn here would be covered by the roster, the charge rail and its entity models.
     * The whole page - base, content, confirmation and tooltip - is therefore painted by
     * {@link #renderTopOverlay} while the HUD is drawn, and by this pass only while the player hid the HUD
     * ({@code hideGui}), which is the one case where that hook does not run. Exactly one pass draws it.</p>
     */
    @Override protected void drawContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (tooltipLayer() != TooltipLayer.CONTENT) return;
        drawFrame(graphics, mouseX, mouseY);
    }

    /** The page above the HUD: the roster, the rail and the portraits can no longer cover it. */
    @Override public void renderTopOverlay(GuiGraphics graphics, int pointerX, int pointerY, float partialTick) {
        if (tooltipLayer() != TooltipLayer.TOP_OVERLAY) return;
        Draw.atDepth(graphics, TacticalSurface.TOP_OVERLAY_DEPTH, () -> drawFrame(graphics, pointerX, pointerY));
    }

    private static TooltipLayer tooltipLayer() { return TooltipLayer.forFrame(hudHidden()); }

    private static boolean hudHidden() {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        return minecraft != null && minecraft.options != null && minecraft.options.hideGui;
    }

    private void drawFrame(GuiGraphics graphics, int mouseX, int mouseY) {
        ensureLayout();
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        double scale = layout.scale();
        long now = Util.getMillis();
        double enter = Motion.easeOutCubic(Motion.progress(now - openedAt, Motion.PANEL_ENTER_MS));
        hovered = hoveredId(mouseX, mouseY);
        updateTooltip(now, mouseX, mouseY);

        // Entrance, as frozen by the specification: the panel base is opaque from the very first frame and only
        // the content fades in over 220ms. Nothing is translated, so every hit box and every scissor rectangle
        // stays exactly where the layout put it, and nothing here touches GuiGraphics beyond Draw.
        contentAlpha = enter;
        drawPage(graphics, font, scale, mouseX, mouseY, now);
    }

    /** A content colour faded by the entrance progress; the panel base never goes through here. */
    private int a(int color) { return Motion.withAlpha(color, contentAlpha); }

    /**
     * The page's emphasis colour, resolved from the live theme.
     *
     * <p>The frozen 0.25 specification makes the accent follow the player's selected theme, so the page asks
     * the shared palette instead of painting the slate's fixed red: this is the one place the character page
     * maps emphasis, and no colour value is decided here.</p>
     */
    private static int accent() { return UiPalette.accent(); }

    /** The darker emphasis, for chips that sit on the accent. Also theme-resolved. */
    private static int accentDark() { return UiPalette.accentDark(); }

    /**
     * How far above the page's own surface the confirmation layer is lifted.
     *
     * <p>Being drawn later is not the same as being in front: the page's body is drawn inside a scissor and
     * the HUD's lane portraits are real entity models pushed hundreds of units deep, so coplanar layers can
     * still interleave. Each layer is therefore flushed into its own plane through {@link Draw#atDepth}, and the
     * base is never raised a second time - that would lift it along with its own popup and change nothing.</p>
     */
    private static final float POPUP_DEPTH = 60.0F;
    /** The tooltip belongs to the highest interactive layer only, so it sits above the confirmation. */
    private static final float TOOLTIP_DEPTH = 120.0F;

    private void drawPage(GuiGraphics graphics, Font font, double scale, int mouseX, int mouseY, long now) {
        drawBase(graphics, font, scale);
        if (layout.popup().w() > 0) {
            Draw.atDepth(graphics, POPUP_DEPTH, () -> drawPopup(graphics, font, scale, mouseX, mouseY));
        }
        if (!tipPrimary.isEmpty() && now - tipSince >= Motion.TOOLTIP_DELAY_MS) {
            Draw.atDepth(graphics, TOOLTIP_DEPTH, () -> drawTooltip(graphics, font, scale));
        }
    }

    /** The page itself: base, title, level block, the scrolling body, status, separator and footer. */
    private void drawBase(GuiGraphics graphics, Font font, double scale) {
        Draw.shadow(graphics, layout.panel(), px(Scale.PANEL_SHADOW, scale), Slate.SHADOW, true);
        Draw.slab(graphics, layout.panel(), Slate.FLOAT);

        String title = text(Keys.TITLE);
        Draw.scaledText(graphics, font, TextMetrics.ellipsis(title,
                layout.close().x() - layout.title().x() - px(4, scale), scale, font::width),
                layout.title().x(), baseline(layout.title(), scale), scale, a(Slate.TEXT));
        drawCloseButton(graphics, scale);
        Draw.accentRule(graphics, layout.accentRule());

        drawLevelBlock(graphics, font, scale);
        drawBody(graphics, font, scale);
        String status = statusText(Util.getMillis());
        if (status != null && layout.statusRow().h() > 0) {
            int color = statusColor(Util.getMillis());
            Draw.scaledText(graphics, font, TextMetrics.ellipsis(status, layout.statusRow().w(), scale, font::width),
                    layout.statusRow().x(), baseline(layout.statusRow(), scale), scale, a(color));
        }
        Draw.separator(graphics, layout.separator());
        for (CharacterLayout.Area area : layout.buttons()) drawFooterButton(graphics, font, area, scale);
    }

    /** The close symbol, drawn from the user's picture: the same plain surface, no extra frame. */
    private void drawCloseButton(GuiGraphics graphics, double scale) {
        Rect rect = layout.close();
        boolean hover = CharacterLayout.CLOSE.equals(hovered);
        boolean focused = CharacterLayout.CLOSE.equals(focus);
        Draw.chip(graphics, rect, a(hover || focused ? Slate.HOVER : Slate.RAISE));
        Draw.sprite(graphics, Draw.ICON_CLOSE, rect, a(Slate.TEXT));
    }

    private void drawLevelBlock(GuiGraphics graphics, Font font, double scale) {
        CharacterStatePacket state = model.state();
        boolean known = state != null && state.profileValid();
        String level = known ? CharacterFormat.level(state.level(), state.maxLevel()) : CharacterFormat.UNKNOWN;
        valueRow(graphics, font, scale, layout.levelRows().get(0), text(Keys.LEVEL), level, Slate.TEXT);
        String xp = known && state.syncAvailable() ? CharacterFormat.integer(state.xpAvailable())
                : CharacterFormat.UNKNOWN;
        boolean shortOfXp = known && state.xpAvailable() < state.xpToNextLevel();
        valueRow(graphics, font, scale, layout.levelRows().get(1), text(Keys.XP), xp,
                shortOfXp ? Slate.HP : Slate.AP);
        String cost;
        int costColor = Slate.TEXT;
        if (!known) {
            cost = CharacterFormat.UNKNOWN;
            costColor = Slate.TEXT3;
        } else if (state.level() >= state.maxLevel()) {
            cost = text(Keys.AT_CAP);
            costColor = Slate.TEXT3;
        } else {
            cost = CharacterFormat.xp(state.xpToNextLevel()) + " · " + text(Keys.POINTS_GRANTED,
                    Integer.toString(state.nextLevelPoints()));
        }
        valueRow(graphics, font, scale, layout.levelRows().get(2), text(Keys.COST), cost, costColor);
        String unspent = known ? CharacterFormat.integer(state.unspent()) : CharacterFormat.UNKNOWN;
        valueRow(graphics, font, scale, layout.unspentRow(), text(Keys.UNSPENT), unspent,
                known && state.unspent() > 0 ? accent() : Slate.TEXT3);
    }

    /**
     * The body: the six stat rows and the derived block inside one clipped, scrolling viewport.
     *
     * <p>The frozen specification makes them a single viewport, so the scissor covers both and a hidden item
     * - one the layout left at {@link Rect#EMPTY} because it did not fit - is skipped instead of being drawn
     * at the origin. Nothing else on the page scrolls.</p>
     */
    private void drawBody(GuiGraphics graphics, Font font, double scale) {
        Rect viewport = layout.statViewport();
        Draw.scissor(graphics, viewport, () -> {
            drawDerived(graphics, font, scale);
            for (CharacterLayout.StatRow row : layout.statRows()) {
                drawStatRow(graphics, font, scale, row);
            }
        });
        drawViewportMarks(graphics, scale);
    }

    /** The 1px viewport rules and the 2px bar; they exist only while part of the body is out of sight. */
    private void drawViewportMarks(GuiGraphics graphics, double scale) {
        if (!layout.scrollable()) return;
        if (layout.viewportTopRule().h() > 0) Draw.fill(graphics, layout.viewportTopRule(), a(Slate.LINE));
        if (layout.viewportBottomRule().h() > 0) Draw.fill(graphics, layout.viewportBottomRule(), a(Slate.LINE));
        Rect track = layout.scrollBar();
        if (track.w() <= 0 || track.h() <= 0) return;
        Draw.fill(graphics, track, a(Slate.SUNK));
        int total = Math.max(1, layout.totalRows());
        int visible = Math.max(1, layout.visibleItems());
        int thumb = Math.max(px(ScrollList.MIN_THUMB, scale), track.h() * visible / total);
        thumb = Math.max(1, Math.min(track.h(), thumb));
        int travel = Math.max(0, track.h() - thumb);
        int maximum = Math.max(0, layout.maxScrollOffset());
        int offset = maximum == 0 ? 0 : travel * layout.scrollOffset() / maximum;
        Draw.fill(graphics, Rect.of(track.x(), track.y() + offset, track.w(), thumb), a(Slate.LINE));
    }

    private void drawStatRow(GuiGraphics graphics, Font font, double scale, CharacterLayout.StatRow row) {
        CharacterStatePacket state = model.state();
        boolean known = state != null && state.profileValid();
        Rect rect = row.row();
        Draw.chip(graphics, rect, a(Slate.RAISE));
        int pad = px(3, scale);
        int baseline = baseline(rect, scale);
        String name = text(Keys.statAbbr(row.stat()));
        int nameWidth = Math.min(px(40, scale), TextMetrics.width(font.width(name), scale));
        Draw.scaledText(graphics, font, TextMetrics.ellipsis(name, nameWidth, scale, font::width), rect.x() + pad,
                baseline, scale, a(known ? Slate.TEXT : Slate.TEXT3));
        Integer display = model.display(row.stat());
        String current = display == null ? CharacterFormat.UNKNOWN : Integer.toString(display - model.draft().increment(row.stat()));
        Draw.scaledText(graphics, font, current, rect.x() + px(70, scale) - TextMetrics.width(font.width(current), scale),
                baseline, scale, a(known ? Slate.TEXT : Slate.TEXT3));
        int increment = model.draft().increment(row.stat());
        if (increment > 0 && display != null) {
            String arrow = "→";
            String drafted = Integer.toString(display);
            int arrowX = rect.x() + px(80, scale);
            Draw.scaledText(graphics, font, arrow, arrowX, baseline, scale, a(Slate.TEXT3));
            Draw.scaledText(graphics, font, drafted,
                    rect.x() + px(110, scale) - TextMetrics.width(font.width(drafted), scale), baseline, scale,
                    a(accent()));
            String chip = CharacterFormat.chip(increment);
            if (!chip.isEmpty()) {
                int chipWidth = TextMetrics.width(font.width(chip), scale) + px(Motion.CHIP_PAD_X, scale) * 2;
                Rect chipRect = layout.chipRect(row, chipWidth);
                Draw.chip(graphics, chipRect, a(accentDark()));
                Draw.scaledText(graphics, font, chip, chipRect.x() + px(Motion.CHIP_PAD_X, scale),
                        baseline(chipRect, scale), scale, a(Slate.TEXT));
            }
        }
        drawStatStepper(graphics, row.minus(), Draw.ICON_MINUS,
                layout.enabled(CharacterLayout.minusId(row.stat())),
                CharacterLayout.minusId(row.stat()).equals(hovered), scale);
        drawStatStepper(graphics, row.plus(), Draw.ICON_PLUS,
                layout.enabled(CharacterLayout.plusId(row.stat())),
                CharacterLayout.plusId(row.stat()).equals(hovered), scale);
    }

    /**
     * One stat stepper: the same dark round base in every state, and only the symbol's colour says whether the
     * point can be taken.
     *
     * <p>The base, the symbol's rectangle and the hit rectangle all come from the same layout and the same
     * enable decision, so a control can never look available and then refuse the press. The symbol itself is
     * the user's picture, centred by whole pixels - no font metric decides where it lands.</p>
     */
    private void drawStatStepper(GuiGraphics graphics, Rect rect, Draw.Sprite symbol, boolean enabled,
                                 boolean hover, double scale) {
        Draw.disc(graphics, rect, a(Slate.SUNK));
        Draw.sprite(graphics, symbol, rect, a(enabled ? Slate.TEXT : Slate.TEXT3));
    }

    /**
     * The derived block: one title and six cells, each drawn only when the layout really placed it.
     *
     * <p>A cell the body viewport did not reach is {@link Rect#EMPTY} - it has no rectangle, no hover and no
     * hit - so it is skipped here rather than drawn at the page's origin. The hover explanation lives in
     * {@link #describe(String)}, i.e. it is decided by the tooltip source, not by whatever the drawing loop
     * happened to pass the pointer.</p>
     */
    private void drawDerived(GuiGraphics graphics, Font font, double scale) {
        Rect title = layout.derivedTitle();
        if (title.w() > 0 && title.h() > 0) {
            Draw.scaledText(graphics, font, text(Keys.DERIVED), title.x(), baseline(title, scale), scale,
                    a(Slate.TEXT2));
        }
        double[] current = model.currentDerived();
        double[] preview = model.previewDerived();
        for (CharacterStat stat : CharacterStat.values()) {
            Rect cell = layout.derivedCells().get(stat.index());
            if (cell.w() <= 0 || cell.h() <= 0) continue;
            String label = text(Keys.derivedName(stat));
            String now = current == null ? CharacterFormat.UNKNOWN : derivedValue(stat, current[stat.index()]);
            String next = preview == null ? null : derivedValue(stat, preview[stat.index()]);
            int line = baseline(cell, scale);
            int used;
            if (next != null && !next.equals(now)) {
                // The specification's draft column: the current value keeps its own colour and the previewed
                // one follows an arrow in the accent. A preview that does not answer this draft is never
                // shown, which is why it comes from the model and not from the last packet seen.
                int nextWidth = TextMetrics.width(font.width(next), scale);
                Draw.scaledText(graphics, font, next, cell.right() - nextWidth, line, scale, a(accent()));
                int arrowWidth = TextMetrics.width(font.width(ARROW), scale);
                int arrowX = cell.right() - nextWidth - px(2, scale) - arrowWidth;
                Draw.scaledText(graphics, font, ARROW, arrowX, line, scale, a(Slate.TEXT3));
                int nowWidth = TextMetrics.width(font.width(now), scale);
                int nowX = arrowX - px(2, scale) - nowWidth;
                // Whole values win over decoration: when both do not fit, the old value is dropped rather
                // than drawn clipped.
                if (nowX >= cell.x() + px(14, scale)) {
                    Draw.scaledText(graphics, font, now, nowX, line, scale, a(Slate.TEXT2));
                    used = cell.right() - nowX;
                } else {
                    used = cell.right() - arrowX;
                }
            } else {
                int width = TextMetrics.width(font.width(now), scale);
                Draw.scaledText(graphics, font, now, cell.right() - width, line, scale, a(Slate.TEXT));
                used = width;
            }
            int labelBudget = Math.max(1, cell.w() - used - px(4, scale));
            Draw.scaledText(graphics, font, TextMetrics.ellipsis(label, labelBudget, scale, font::width),
                    cell.x(), line, scale, a(Slate.TEXT2));
        }
    }

    /** The arrow between the current and the drafted derived value. */
    private static final String ARROW = "→";

    /**
     * One stat's per-point effect, with the server's own coefficient substituted.
     *
     * <p>The sentence used to hard-code the default {@code +2}/{@code +5}/… which made it lie the moment a
     * pack changed the rules. The number now comes from the snapshot's coefficients and nowhere else; with no
     * readable snapshot there is no claim to make, so the line is empty and the tooltip shows the name only.</p>
     *
     * <p>Endurance is worded in percentage points of the max-AP factor, not in AP: the rule adds two factor
     * points, which is a different number from the finished AP a player sees.</p>
     */
    private String effectText(CharacterStat stat) {
        CharacterStatePacket state = model.state();
        if (state == null || !state.profileValid() || !state.syncAvailable()) return "";
        double[] coefficients = state.coefficients();
        if (stat.index() >= coefficients.length) return "";
        return ClientText.get(Keys.statEffect(stat), CharacterFormat.coefficient(coefficients[stat.index()]));
    }

    private void drawFooterButton(GuiGraphics graphics, Font font, CharacterLayout.Area area, double scale) {
        String id = area.id();
        boolean hover = id.equals(hovered);
        String label;
        if (CharacterLayout.LEVEL_UP.equals(id)) label = text(Keys.LEVEL_UP);
        else if (CharacterLayout.DISCARD.equals(id)) label = text(Keys.DISCARD);
        else label = text(Keys.CONFIRM);
        boolean enabled = area.enabled();
        if (model.waiting() && (CharacterLayout.CONFIRM.equals(id) || CharacterLayout.LEVEL_UP.equals(id))) {
            label = text(Keys.WAITING_BUTTON);
        }
        drawButton(graphics, font, id, area.rect(), label, enabled, hover, scale);
    }

    /**
     * Why one footer control is inert right now, in the wording the server uses for the same refusal.
     *
     * <p>It reads only what the model decided - the read-only reason, the level cap, the point ledger - so the
     * sentence cannot disagree with the button's own enable condition.</p>
     */
    private String disabledReason(String id) {
        CharacterStatePacket state = model.state();
        ProgressionReason readOnly = model.readOnly();
        if (readOnly != null) return text(readOnly.key());
        if (state == null) return text(ProgressionReason.PROFILE_UNKNOWN.key());
        if (CharacterLayout.LEVEL_UP.equals(id) || CharacterLayout.POPUP_CONFIRM.equals(id)) {
            if (state.level() >= state.maxLevel()) return text(Keys.AT_CAP);
            return text(ProgressionReason.INSUFFICIENT_XP.key());
        }
        if (CharacterLayout.CONFIRM.equals(id)) {
            if (model.draft().isEmpty()) return text(ProgressionReason.NOTHING_TO_ALLOCATE.key());
            return text(ProgressionReason.INSUFFICIENT_POINTS.key());
        }
        return null;
    }

    private void drawPopup(GuiGraphics graphics, Font font, double scale, int mouseX, int mouseY) {
        Draw.shadow(graphics, layout.popup(), px(Scale.SMALL_SHADOW, scale), Slate.SHADOW_SM, false);
        Draw.chip(graphics, layout.popup(), Slate.FLOAT);
        Draw.scaledText(graphics, font, text(Keys.POPUP_TITLE), layout.popupTitle().x(),
                baseline(layout.popupTitle(), scale), scale, a(Slate.TEXT));
        CharacterStatePacket state = model.state();
        List<Rect> rows = layout.popupRows();
        if (rows.size() == 3 && state != null) {
            popupRow(graphics, font, scale, rows.get(0), text(Keys.POPUP_COST),
                    CharacterFormat.xp(state.xpToNextLevel()), Slate.AP);
            popupRow(graphics, font, scale, rows.get(1), text(Keys.POPUP_LEVEL),
                    CharacterFormat.levelStep(state.level()), accent());
            popupRow(graphics, font, scale, rows.get(2), text(Keys.POPUP_POINTS),
                    CharacterFormat.chip(state.nextLevelPoints()), accent());
        }
        for (int index = 0; index < layout.popupNote().size(); index++) {
            Rect note = layout.popupNote().get(index);
            String wrapped = index == 0 ? text(Keys.POPUP_NOTE) : "";
            if (wrapped.isEmpty()) continue;
            Draw.scaledText(graphics, font, TextMetrics.ellipsis(wrapped, note.w(), scale, font::width), note.x(),
                    baseline(note, scale), scale, a(Slate.TEXT2));
        }
        for (CharacterLayout.Area area : layout.popupButtons()) {
            boolean confirm = CharacterLayout.POPUP_CONFIRM.equals(area.id());
            String label = text(confirm ? Keys.POPUP_CONFIRM : Keys.POPUP_CANCEL);
            boolean enabled = area.enabled();
            if (confirm && model.waiting()) label = text(Keys.WAITING_BUTTON);
            drawButton(graphics, font, area.id(), area.rect(), label, enabled, area.id().equals(hovered), scale);
        }
    }

    private void drawTooltip(GuiGraphics graphics, Font font, double scale) {
        Tooltip.Content content = Tooltip.content(tipPrimary, tipSecondary, scale, font::width);
        if (content.isEmpty()) return;
        var window = net.minecraft.client.Minecraft.getInstance().getWindow();
        Rect box = Tooltip.place(Rect.of(tipX, tipY, 1, 1), Tooltip.width(content, scale, font::width),
                Tooltip.height(content, scale), window.getGuiScaledWidth(), window.getGuiScaledHeight(),
                tipX, tipY, px(Tooltip.GAP, scale));
        Draw.shadow(graphics, box, px(Scale.SMALL_SHADOW, scale), Slate.SHADOW_SM, false);
        Draw.chip(graphics, box, Slate.FLOAT);
        int x = Tooltip.textLeft(box, scale);
        int y = Tooltip.textTop(box, scale);
        for (String line : content.primary()) {
            Draw.scaledText(graphics, font, line, x, y, scale, a(Slate.TEXT));
            y += px(Motion.LINE, scale);
        }
        for (String line : content.secondary()) {
            Draw.scaledText(graphics, font, line, x, y, scale, a(Slate.TEXT2));
            y += px(Motion.LINE, scale);
        }
    }

    /**
     * Which thing the pointer is explaining this frame, as one stable identity.
     *
     * <p>An explanation is not a control: a stat row and a derived cell own no press, so they are resolved
     * here from their real rectangles instead of being added to the hit order - adding them would steal the
     * {@code +}/{@code −} buttons that sit inside the same row. The order is therefore: an open confirmation
     * first (it owns the pointer and the page under it explains nothing), then the row or cell under the
     * pointer, then an actual control. Because the identity is one string, moving from a row to a derived
     * cell - or from one row to the next - restarts the 350ms delay instead of inheriting the previous one.</p>
     */
    private String tipSourceAt(int mouseX, int mouseY) {
        if (layout == null) return "";
        if (layout.popup().w() > 0) {
            var box = layout.hit(mouseX, mouseY);
            return box == null ? "" : box.id();
        }
        var row = layout.rowAt(mouseX, mouseY);
        if (row != null) return CharacterLayout.rowId(row.stat());
        var cell = layout.derivedCellAt(mouseX, mouseY);
        if (cell != null) return CharacterLayout.derivedCellId(cell);
        var box = layout.hit(mouseX, mouseY);
        return box == null ? "" : box.id();
    }

    /** Resolves the tooltip's two groups for one source; both stay empty when the source says nothing. */
    private void describe(String source) {
        CharacterStatePacket state = model.state();
        boolean known = state != null && state.profileValid();
        if (!source.isEmpty()) {
            for (CharacterStat stat : CharacterStat.values()) {
                if (CharacterLayout.rowId(stat).equals(source)) {
                    tipPrimary = text(Keys.stat(stat));
                    tipSecondary = TooltipSecondary.of(stat, effectText(stat), model.draft().increment(stat),
                            known ? state.invested()[stat.index()] : 0);
                    return;
                }
                if (CharacterLayout.derivedCellId(stat).equals(source)) {
                    // A derived cell has no per-point gain and no points of its own: it explains the metric
                    // itself. Reusing the stat row's effect sentence here claimed "per point" for a value that
                    // is a total, which is what the user saw under the table.
                    Keys.Tip tip = Keys.derivedTip(stat);
                    tipPrimary = text(tip.primary());
                    tipSecondary = text(tip.secondary());
                    return;
                }
            }
            // A control only explains itself when it cannot act: the reason is the server's own wording.
            String label = controlLabel(source);
            if (label != null) {
                boolean enabled = controlEnabled(source);
                if (!enabled) {
                    String reason = disabledReason(source);
                    if (reason != null) {
                        tipPrimary = label;
                        tipSecondary = reason;
                    }
                }
                return;
            }
        }
    }

    /** The wording of a pressable control by id, or null when the id is not a control of this page. */
    private String controlLabel(String id) {
        if (CharacterLayout.CLOSE.equals(id)) return "×";
        if (CharacterLayout.LEVEL_UP.equals(id)) return text(Keys.LEVEL_UP);
        if (CharacterLayout.DISCARD.equals(id)) return text(Keys.DISCARD);
        if (CharacterLayout.CONFIRM.equals(id)) return text(Keys.CONFIRM);
        if (CharacterLayout.POPUP_CONFIRM.equals(id)) return text(Keys.POPUP_CONFIRM);
        if (CharacterLayout.POPUP_CANCEL.equals(id)) return text(Keys.POPUP_CANCEL);
        return null;
    }

    /** True when that control would act right now, asked of the same boxes the pointer presses. */
    private boolean controlEnabled(String id) {
        for (Box box : layout.boxes()) {
            if (box.id().equals(id)) return box.enabled();
        }
        return false;
    }

    /**
     * Tracks the tooltip's subject, so the 350ms delay restarts whenever the pointer moves to something else.
     */
    private void updateTooltip(long now, int mouseX, int mouseY) {
        String source = tipSourceAt(mouseX, mouseY);
        tipPrimary = "";
        tipSecondary = "";
        describe(source);
        if (!source.equals(activeTipSource)) {
            activeTipSource = source;
            tipSince = now;
        }
        tipX = mouseX;
        tipY = mouseY;
    }

    private String activeTipSource = "";

    // ---- input ------------------------------------------------------------------------------------

    /**
     * A press only starts a capture; the control acts on the release over the same control.
     *
     * <p>Only the left button can activate anything - a right or middle click is swallowed by the modal page
     * but never upgrades or allocates - and the capture is what makes "press on one control, drag away,
     * release elsewhere" a no-op instead of an accidental confirmation.</p>
     */
    @Override public boolean mouseClicked(double pointerX, double pointerY, int button) {
        ensureLayout();
        if (!CharacterModalPolicy.activates(button)) {
            capture.cancel();
            return true;
        }
        String id = hitId(pointerX, pointerY);
        if (id == null) {
            capture.cancel();
            return true;
        }
        capture.press(id);
        return true;
    }

    @Override public boolean mouseReleased(double pointerX, double pointerY, int button) {
        ensureLayout();
        if (!CharacterModalPolicy.activates(button)) {
            capture.cancel();
            return true;
        }
        String pressed = capture.release(hitId(pointerX, pointerY));
        if (pressed != null) activate(pressed);
        return true;
    }

    /** A drag belongs to nothing: the page is modal, so a HUD size drag can never start behind it. */
    @Override public boolean mouseDragged(double pointerX, double pointerY, int button, double dragX, double dragY) {
        capture.cancel();
        return true;
    }

    private String hitId(double x, double y) {
        var box = layout.hitEnabled(x, y);
        return box == null ? null : box.id();
    }

    /** Runs the control with this id; every branch is one transaction, never a local estimate. */
    void activate(String id) {
        capture.cancel();
        if (id == null) return;
        if (CharacterLayout.CLOSE.equals(id)) {
            onClose();
            return;
        }
        if (CharacterLayout.POPUP_CANCEL.equals(id)) {
            model.cancelUpgradePopup();
            focus = CharacterLayout.POPUP_CANCEL;
            rebuild();
            return;
        }
        if (CharacterLayout.POPUP_CONFIRM.equals(id)) {
            model.requestUpgrade();
            rebuild();
            return;
        }
        if (CharacterLayout.LEVEL_UP.equals(id)) {
            model.openUpgradePopup();
            rebuild();
            return;
        }
        if (CharacterLayout.DISCARD.equals(id)) {
            model.cancelDraft();
            rebuild();
            return;
        }
        if (CharacterLayout.CONFIRM.equals(id)) {
            model.requestAllocate();
            rebuild();
            return;
        }
        for (CharacterStat stat : CharacterStat.values()) {
            if (CharacterLayout.minusId(stat).equals(id)) {
                model.remove(stat);
                rebuild();
                return;
            }
            if (CharacterLayout.plusId(stat).equals(id)) {
                model.add(stat);
                rebuild();
                return;
            }
        }
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        ensureLayout();
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        boolean hotbar = false;
        for (var mapping : minecraft.options.keyHotbarSlots) {
            if (mapping.matches(keyCode, scanCode)) hotbar = true;
        }
        boolean inventory = minecraft.options.keyInventory.matches(keyCode, scanCode);
        CharacterModalPolicy.Key key = CharacterModalPolicy.classify(keyCode, keyCode == GLFW.GLFW_KEY_TAB,
                keyCode == GLFW.GLFW_KEY_TAB && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0,
                keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER,
                keyCode == GLFW.GLFW_KEY_SPACE, keyCode == GLFW.GLFW_KEY_ESCAPE, hotbar, inventory);
        CharacterModalPolicy.Route route = CharacterModalPolicy.route(true, model.upgradePopupOpen(), key);
        switch (route) {
            case CLOSE_POPUP -> {
                model.cancelUpgradePopup();
                rebuild();
                return true;
            }
            case CLOSE_PAGE -> {
                onClose();
                return true;
            }
            case PAGE -> {
                handlePageKey(key, modifiers);
                return true;
            }
            case CONSUME -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void handlePageKey(CharacterModalPolicy.Key key, int modifiers) {
        if (model.upgradePopupOpen()) {
            if (key == CharacterModalPolicy.Key.TAB || key == CharacterModalPolicy.Key.SHIFT_TAB) {
                focus = CharacterLayout.POPUP_CANCEL.equals(focus) ? CharacterLayout.POPUP_CONFIRM
                        : CharacterLayout.POPUP_CANCEL;
                rebuild();
            } else if (key == CharacterModalPolicy.Key.ENTER || key == CharacterModalPolicy.Key.SPACE) {
                activate(CharacterLayout.POPUP_CONFIRM.equals(focus) ? CharacterLayout.POPUP_CONFIRM
                        : CharacterLayout.POPUP_CANCEL);
            }
            return;
        }
        if (key == CharacterModalPolicy.Key.TAB || key == CharacterModalPolicy.Key.SHIFT_TAB) {
            focus = layout.focusAfter(focus, key == CharacterModalPolicy.Key.SHIFT_TAB);
            rebuild();
            return;
        }
        if (key == CharacterModalPolicy.Key.ENTER || key == CharacterModalPolicy.Key.SPACE) {
            if (focus == null) return;
            activate(focus);
        }
    }

    /**
     * One notch scrolls the table by exactly one row and never reaches what is behind the page.
     *
     * <p>The direction is the shared list's: a notch downwards (a negative GLFW delta) moves the window
     * forward, so the content rises, exactly like the roster and the journal. {@code CharacterModalPolicy}
     * owns the sign, this method only applies the step and clamps at both ends.</p>
     */
    @Override public boolean scrollAt(double guiX, double guiY, double delta) {
        ensureLayout();
        int step = CharacterModalPolicy.wheelStep(true, delta);
        if (step == 0 || !layout.scrollable()) return false;
        // A scroll cancels the press it interrupts, so the release that follows cannot activate anything.
        capture.cancel();
        int next = layout.scrolled(step);
        if (next == scroll) return false;
        scroll = next;
        rebuild();
        return true;
    }

    /** The page owns the wheel wherever the pointer is, so nothing behind it scrolls. */
    @Override public boolean mouseScrolled(double pointerX, double pointerY, double scrollX, double scrollY) {
        return true;
    }

    /** A press anywhere on the page belongs to the page, including the area outside the panel. */
    @Override public boolean overUi(double guiX, double guiY) { return true; }

    @Override public boolean consumesClick(double guiX, double guiY) { return true; }

    @Override public boolean consumesWheel() { return model.upgradePopupOpen(); }

    @Override public void onClose() {
        open = null;
        CharacterClientState.clearRefreshDemand();
        // An answer to this visit must not reopen the page after the player closed it.
        CharacterClientState.takeOpenRequest();
        super.onClose();
    }

    // ---- small drawing helpers --------------------------------------------------------------------

    /**
     * One plain button: a stepped surface, no outer frame and no accent bar.
     *
     * <p>Every state this page has is expressed by the surface colour it already uses - disabled is sunk,
     * hover and keyboard focus are the hover surface - so the keyboard and the pointer mark exactly the same
     * thing and neither adds a border the specification removed. Mouse and keyboard still reach the same
     * {@code activate(id)}; the Tab order is the layout's own focus list.</p>
     */
    private void drawButton(GuiGraphics graphics, Font font, String id, Rect rect, String label, boolean enabled,
                            boolean hover, double scale) {
        boolean focused = id.equals(focus);
        Draw.chip(graphics, rect, a(!enabled ? Slate.SUNK : hover || focused ? Slate.HOVER : Slate.RAISE));
        // A one-glyph label is a sign, not prose: folding it to the button's padded width would turn it into an
        // ellipsis and leave the button looking empty.
        String shown = TextMetrics.iconOrEllipsis(label, Math.max(1, rect.w() - px(Motion.BUTTON_PAD_X, scale) * 2),
                scale, font::width);
        int color = a(enabled ? Slate.TEXT : Slate.TEXT3);
        Draw.scaledText(graphics, font, shown, rect.x() + (rect.w() - TextMetrics.width(font.width(shown), scale)) / 2,
                baseline(rect, scale), scale, color);
    }

    private void valueRow(GuiGraphics graphics, Font font, double scale, Rect rect, String label, String value,
                          int valueColor) {
        Draw.scaledText(graphics, font, label, rect.x(), baseline(rect, scale), scale, a(Slate.TEXT2));
        String shown = TextMetrics.fitAfterLabel(value, rect.w(), TextMetrics.width(font.width(label), scale),
                px(4, scale), scale, font::width).shown();
        Draw.scaledText(graphics, font, shown, rect.right() - TextMetrics.width(font.width(shown), scale),
                baseline(rect, scale), scale, a(valueColor));
    }

    private void popupRow(GuiGraphics graphics, Font font, double scale, Rect rect, String label, String value,
                          int valueColor) {
        valueRow(graphics, font, scale, rect, label, value, valueColor);
    }

    private String statusText(long now) {
        ProgressionReason notice = model.notice(now);
        if (notice != null) return text(notice.key());
        ProgressionReason readOnly = model.readOnly();
        if (readOnly == ProgressionReason.SYNC_UNAVAILABLE) return text(Keys.STATUS_SYNCING);
        if (readOnly != null) return text(readOnly.key());
        if (model.draftStale()) return text(Keys.STATUS_STALE);
        if (model.waiting()) return text(Keys.STATUS_WAITING);
        return null;
    }

    private int statusColor(long now) {
        if (model.notice(now) != null) return Slate.HP;
        if (model.draftStale()) return Slate.HP;
        if (model.readOnly() != null) return Slate.TEXT3;
        return Slate.TEXT2;
    }

    private String hoveredId(int mouseX, int mouseY) {
        var box = layout == null ? null : layout.hit(mouseX, mouseY);
        return box == null ? null : box.id();
    }

    private String derivedValue(CharacterStat stat, double value) {
        return switch (stat) {
            case VIGOR, MIND, STRENGTH -> CharacterFormat.amount(value);
            case ENDURANCE -> CharacterFormat.ap(value);
            case DEXTERITY, INTELLIGENCE -> CharacterFormat.multiplier(value);
        };
    }

    private static int baseline(Rect rect, double scale) {
        int line = px(Motion.LINE, scale);
        return rect.y() + (rect.h() - line) / 2 + 1;
    }

    private static int px(double design, double scale) { return Scale.effectivePx(design, scale); }

    private static String text(String key, Object... args) { return ClientText.get(key, args); }

    @Override protected String probeJson() {
        ensureLayout();
        StringBuilder nodes = new StringBuilder();
        boolean first = true;
        for (var box : layout.boxes()) {
            Rect rect = box.rect();
            if (rect.w() <= 0 || rect.h() <= 0) continue;
            if (!first) nodes.append(',');
            first = false;
            nodes.append("{\"id\":\"").append(box.id()).append("\",\"x\":").append(rect.x())
                    .append(",\"y\":").append(rect.y()).append(",\"w\":").append(rect.w())
                    .append(",\"h\":").append(rect.h()).append(",\"interactive\":").append(box.interactive())
                    .append(",\"enabled\":").append(box.enabled())
                    .append(",\"focused\":").append(box.id().equals(focus))
                    .append(",\"hovered\":").append(box.id().equals(hovered)).append('}');
        }
        // The body's passive rectangles are reported too, but only the ones this frame really drew: a driver
        // needs to know where a row or a derived cell is, and a scrolled-out one must not appear as if it did.
        StringBuilder rows = new StringBuilder();
        boolean firstRow = true;
        for (CharacterLayout.StatRow row : layout.statRows()) {
            if (!firstRow) rows.append(',');
            firstRow = false;
            rows.append(rect(CharacterLayout.rowId(row.stat()), row.row()));
        }
        StringBuilder derived = new StringBuilder();
        boolean firstCell = true;
        for (CharacterStat stat : CharacterStat.values()) {
            Rect cell = layout.derivedCells().get(stat.index());
            if (cell.w() <= 0 || cell.h() <= 0) continue;
            if (!firstCell) derived.append(',');
            firstCell = false;
            derived.append(rect(CharacterLayout.derivedCellId(stat), cell));
        }
        Rect viewport = layout.statViewport();
        // The model block answers "did a press really send something": the probe reports the outstanding
        // transaction and its request id, plus the draft and the page's own gate booleans.
        int[] draft = model.draft().increments();
        StringBuilder draftJson = new StringBuilder("[");
        for (int index = 0; index < draft.length; index++) {
            if (index > 0) draftJson.append(',');
            draftJson.append(draft[index]);
        }
        draftJson.append(']');
        String readOnly = model.readOnly() == null ? "" : model.readOnly().name();
        String status = statusText(Util.getMillis());
        return String.format(Locale.ROOT,
                "{\"gui\":{\"w\":%d,\"h\":%d,\"scale\":%.3f,\"preset\":%d,\"narrow\":%b},"
                        + "\"panel\":{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d},"
                        + "\"body\":{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d,\"scrollable\":%b,\"scroll\":%d,"
                        + "\"maxScroll\":%d,\"items\":%d,\"visible\":%d},"
                        + "\"status\":\"%s\",\"scroll\":%d,\"focus\":\"%s\",\"rows\":[%s],\"derived\":[%s],"
                        + "\"model\":{\"pending\":\"%s\",\"requestId\":%d,\"draft\":%s,\"draftTotal\":%d,"
                        + "\"canConfirm\":%b,\"canUpgrade\":%b,\"waiting\":%b,\"previewPending\":%b,"
                        + "\"readOnly\":\"%s\"},"
                        + "\"nodes\":[%s]}",
                layout.guiWidth(), layout.guiHeight(), layout.scale(), UiZoom.percent(), layout.narrow(),
                layout.panel().x(), layout.panel().y(), layout.panel().w(), layout.panel().h(),
                viewport.x(), viewport.y(), viewport.w(), viewport.h(), layout.scrollable(),
                layout.scrollOffset(), layout.maxScrollOffset(), layout.totalRows(), layout.visibleItems(),
                status == null ? "" : status.replace("\"", ""),
                layout.scrollOffset(), focus == null ? "" : focus, rows, derived,
                model.pending().name(), model.pendingRequestId(), draftJson, model.draft().total(),
                model.canConfirmAllocation(), model.canUpgrade(), model.waiting(), model.previewPending(),
                readOnly, nodes);
    }

    private static String rect(String id, Rect rect) {
        return "{\"id\":\"" + id + "\",\"x\":" + rect.x() + ",\"y\":" + rect.y()
                + ",\"w\":" + rect.w() + ",\"h\":" + rect.h() + '}';
    }

    /** Wording keys of this page; the shared language tables are merged by their single writer. */
    static final class Keys {
        static final String PREFIX = "tactical.character.";
        static final String TITLE = PREFIX + "title";
        static final String LEVEL = PREFIX + "level";
        static final String XP = PREFIX + "xp";
        static final String COST = PREFIX + "cost";
        static final String AT_CAP = PREFIX + "at_cap";
        static final String POINTS_GRANTED = PREFIX + "points_granted";
        static final String UNSPENT = PREFIX + "unspent";
        static final String DERIVED = PREFIX + "derived";
        static final String CONFIRM = PREFIX + "confirm";
        static final String DISCARD = PREFIX + "discard";
        static final String LEVEL_UP = PREFIX + "level_up";
        static final String WAITING_BUTTON = PREFIX + "waiting_button";
        static final String POPUP_TITLE = PREFIX + "popup.title";
        static final String POPUP_COST = PREFIX + "popup.cost";
        static final String POPUP_LEVEL = PREFIX + "popup.level";
        static final String POPUP_POINTS = PREFIX + "popup.points";
        static final String POPUP_NOTE = PREFIX + "popup.note";
        static final String POPUP_CONFIRM = PREFIX + "popup.confirm";
        static final String POPUP_CANCEL = PREFIX + "popup.cancel";
        static final String STATUS_SYNCING = PREFIX + "status.syncing";
        static final String STATUS_WAITING = PREFIX + "status.waiting";
        static final String STATUS_STALE = PREFIX + "status.stale";

        private Keys() { }

        static String statAbbr(CharacterStat stat) { return PREFIX + "abbr." + stat.id(); }

        static String stat(CharacterStat stat) { return "tactical.stat." + stat.id(); }

        static String statEffect(CharacterStat stat) { return PREFIX + "effect." + stat.id(); }

        static String derivedName(CharacterStat stat) {
            return switch (stat) {
                case VIGOR -> PREFIX + "derived.health";
                case MIND -> PREFIX + "derived.mp";
                case ENDURANCE -> PREFIX + "derived.ap";
                case STRENGTH -> PREFIX + "derived.damage";
                case DEXTERITY -> PREFIX + "derived.charge";
                case INTELLIGENCE -> PREFIX + "derived.power";
            };
        }

        /**
         * What one derived cell's metric <em>is</em>, as its own wording key.
         *
         * <p>Separate from {@link #statEffect} on purpose: a derived value is a finished number (a maximum, a
         * total or a multiplier), not a rate per invested point, so the sentence may not carry a coefficient
         * and may not talk about points at all. It names the thing the server really reads - max health, max
         * MP, the rounded max AP, the vanilla attack damage, the turn-charge multiplier and the multiplier of
         * this mod's <em>skill damage</em> only - so the page cannot promise a heal or a cost change that the
         * rule does not implement.</p>
         */
        static String derivedHelp(CharacterStat stat) { return PREFIX + "derived.help." + stat.id(); }

        /** Both line groups of one tooltip, still as keys; the screen resolves them per frame. */
        record Tip(String primary, String secondary) { }

        /** The two groups a derived cell explains itself with. Never the per-point effect of a stat row. */
        static Tip derivedTip(CharacterStat stat) {
            return new Tip(derivedName(stat), derivedHelp(stat));
        }
    }

    /** The transport: the four payloads of the shared contract, addressed only at this connection. */
    private final class LiveActions implements CharacterPanelModel.Actions {
        @Override public void requestSnapshot(int requestId, boolean openScreen) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tactical.neoforge.network.CharacterRequestPacket(requestId, openScreen));
        }

        @Override public void requestPreview(int requestId, long revision, int[] increments) {
            lastPreviewRequestHint = requestId;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tactical.neoforge.network.CharacterPreviewPacket(requestId, revision, increments));
        }

        @Override public void requestUpgrade(int requestId, long revision) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tactical.neoforge.network.CharacterUpgradePacket(requestId, revision));
        }

        @Override public void requestAllocate(int requestId, long revision, int[] increments) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.tactical.neoforge.network.CharacterAllocatePacket(requestId, revision, increments));
        }
    }

    /** Composes the tooltip's second group for one stat: its effect, then the committed and drafted points. */
    private static final class TooltipSecondary {
        private TooltipSecondary() { }

        static String of(CharacterStat stat, String effect, int drafted, int invested) {
            List<String> parts = new ArrayList<>();
            if (effect != null && !effect.isBlank()) parts.add(effect);
            if (invested > 0 || drafted > 0) parts.add(ClientText.get(CharacterScreen.Keys.PREFIX + "invested",
                    Integer.toString(invested), Integer.toString(drafted)));
            return String.join("\n", parts);
        }
    }
}
