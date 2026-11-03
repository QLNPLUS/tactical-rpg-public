package com.tactical.neoforge.client.ui.hud;

import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.ScrollList;
import com.tactical.neoforge.client.ui.theme.Motion;

import java.util.ArrayList;
import java.util.List;

/**
 * One frame's geometry of the whole battle HUD: the roster, the journal, the charge rail and the
 * passive nearby hint, with the two panels at whatever width and height the user dragged them to.
 *
 * <p>Pure on purpose. Drawing, hit testing, wheel routing, the size handles and the development probe
 * all read this single snapshot, so a portrait can never be drawn where it is not hit and a panel can
 * never be laid out twice with different numbers. Every value is in GUI pixels, already rounded.</p>
 *
 * <p>Saved sizes are 100% design values, exactly like the type-size preset: the actual value is
 * {@code round(design × scale)} and is then clamped for this frame only. Clamping never writes back,
 * so a small window cannot flatten a preference the user dragged on a big one. The width clamp asks
 * the nearest resident panel for its left edge, which is why the charge rail is placed first and the
 * two panels afterwards.</p>
 *
 * <p>The bottom bounds are exactly the specification's two lines: {@code Bres} and {@code Bflo} both
 * stop at the screen edge, the hotbar strip the panel horizontally reaches into and, while a chat
 * screen is open, its edit line. Chat <em>history</em> reserves nothing: the user asked for the left
 * column to extend over the chat messages and paint on top of them, so the two bounds coincide again
 * and only the floating journal's different top edge tells them apart.</p>
 */
public final class HudLayout {

    /** Whether the journal has no body, a title-only strip, an inline body, or a floating body. */
    public enum JournalMode { HIDDEN, HEADER, EMBEDDED, FLOATING }

    /** Portrait frame sizes and rail metrics, all at 100%. */
    public static final int RAIL_HEIGHT = 32;
    public static final int RAIL_TRACK_Y = 26;
    public static final int RAIL_INSET = 2;
    public static final int RAIL_END_TICK = 5;
    public static final int PORTRAIT = 18;
    public static final int PORTRAIT_CURRENT = 20;
    /** The current actor's taller frame is raised above the lane tops. */
    public static final int RAIL_CURRENT_Y = 3;
    public static final int RAIL_LANE0_Y = 8;
    public static final int RAIL_LANE1_Y = 14;
    public static final int RAIL_LANE_STEP = 6;
    public static final int RAIL_WIDTH_STANDARD = 270;

    /** Roster metrics at 100%; a row is 11 + 9 lines tall because of the automatic name wrapping. */
    public static final int ROSTER_ROW_BASE = 11;
    public static final int ROSTER_ROW_GAP = 2;
    public static final int ROSTER_NAME_LEFT = 22;
    public static final int ROSTER_HANDLE_RESERVE = 8;
    /** The journal's handle reserves the same corner on its title row and above its count. */
    public static final int JOURNAL_HANDLE_RESERVE = 8;
    /** Roster bars sit this far below the text: 8 + 9 × lines. */
    public static final int ROSTER_BAR_BASE = 8;
    public static final int ROSTER_FOOTER = 28;
    /** Title line plus the accent rule group (4 above, 1 rule, 5 below). */
    public static final int TITLE_BLOCK = 10;

    /** Readable width range of both panels, in 100% design pixels. */
    public static final int ROSTER_MIN_WIDTH = 120;
    public static final int ROSTER_MAX_WIDTH = 240;
    /** Journal metrics at 100%. */
    public static final int JOURNAL_HEADER = 14;
    public static final int JOURNAL_TOGGLE_WIDTH = 50;
    public static final int JOURNAL_ENTRY_GAP = 2;
    /** Default chip column width at 100%, used until the painter measures the real labels. */
    public static final int JOURNAL_CHIP_WIDTH = 32;
    /** A text column this wide always survives the chip column's clamp, so a row stays readable. */
    public static final int JOURNAL_CHIP_MIN_TEXT = 24;
    /** The journal body's own height preference: default 7 single-line entries, minimum one 2-line entry. */
    public static final int JOURNAL_BODY_DEFAULT = 75;
    public static final int JOURNAL_BODY_MIN = 18;
    public static final int JOURNAL_BODY_MAX = 400;
    public static final int FOOTER_BLOCK = 19;
    /** Smallest floating journal body the specification requires: one two-line entry plus footer. */
    public static final int JOURNAL_FLOAT_MIN = 47;
    /** The journal's reserved minimum height in the standard form, at 100%. */
    public static final int JOURNAL_MIN_STANDARD = 73;

    /** Size handle: 7×7 of dots, an 8×8 reserved corner and a 12×12 hit area, all fixed GUI pixels. */
    public static final int HANDLE_VISUAL = 7;
    public static final int HANDLE_RESERVED = 8;
    public static final int HANDLE_HIT = 12;

    public static final String ROSTER = "roster";
    public static final String ROSTER_TITLE = "roster-title";
    public static final String ROSTER_RULE = "roster-rule";
    public static final String ROSTER_HANDLE = "roster-handle";
    public static final String ROSTER_FOOTER_RULE = "roster-footer-rule";
    public static final String ROSTER_FOOTER_AP = "roster-footer-ap";
    public static final String ROSTER_FOOTER_WEAPON = "roster-footer-weapon";

    public static final String JOURNAL = "journal";
    public static final String JOURNAL_TITLE = "journal-title";
    public static final String JOURNAL_TOGGLE = "journal-toggle";
    public static final String JOURNAL_HANDLE = "journal-handle";
    public static final String JOURNAL_RULE = "journal-rule";
    public static final String JOURNAL_BODY = "journal-body";
    public static final String JOURNAL_FOOTER_RULE = "journal-footer-rule";
    public static final String JOURNAL_STATUS = "journal-status";
    public static final String JOURNAL_COUNT = "journal-count";

    public static final String RAIL = "rail";
    public static final String HINT = "hint";

    /** Which layout the current window and preset use. */
    private static final int HOTBAR_AVOID_WIDTH = 190;
    private static final int HOTBAR_AVOID_HEIGHT = 40;

    /** Total text lines of every roster row, for the name column width this frame actually has. */
    @FunctionalInterface
    public interface RosterRowLines {
        /**
         * @param nameBudget   text column width in GUI pixels
         * @param maxNameLines how many name lines a row may use in the space this frame allows; the
         *                     04-round rule lowers it so one row always fits the list area
         */
        int[] linesFor(int nameBudget, int maxNameLines);
    }

    /**
     * Every retained journal entry's line count for one text width (newest first) together with the
     * entries' own sequence numbers (oldest first), so the layout can stack the retained history in
     * pixels and resolve the browsed anchor against the same content the painter receives.
     */
    public record JournalMeasure(int[] linesNewestFirst, long[] sequencesOldestFirst) {
    }

    /**
     * The journal title row's real measurements, in 100% design pixels: what the title says, what the
     * whole toggle label says, and what the single-glyph icon says. The layout decides with these whether
     * the title and the full label fit side by side, and only then falls back to the icon button.
     */
    public record JournalToggle(int titleWidth, int labelWidth, int iconWidth) {
    }

    /**
     * Measure of the journal's retained history for this text width. The layout owns the pixel stack
     * from here on: it resolves where the body is scrolled to and reports every entry that intersects
     * it, partial entries included.
     */
    @FunctionalInterface
    public interface JournalEntryLines {
        JournalMeasure linesFor(int textWidth);
    }

    /**
     * One frame of input.
     *
     * @param chatInputReserve only the chat <em>edit line</em> when a chat screen is open, so the
     *                         left column stops above it; chat history never reserves anything
     * @param menuOpen      whether a right-anchored tactical panel is on screen: it caps both widths
     * @param rosterWidth   saved roster width at 100%, or null for the 140 default
     * @param rosterHeight  saved roster height at 100%, or null for "as tall as the content"
     * @param journalWidth  saved journal width at 100%, or null for the 140 default
     * @param journalBody   saved journal body height at 100%, or null for the 75 default
     * @param rosterScroll  the roster's row offset, kept for the whole encounter
     * @param hintWidth     the hint box's own width, or 0 when the hint is not shown
     * @param journalAnchor where the journal body is browsed to, or its newest end
     */
    public record Input(int guiWidth, int guiHeight, double scale, Scale.Form form, int chatInputReserve,
                        boolean rosterVisible, int rosterCount, int rosterScroll,
                        JournalMode journalMode, boolean menuOpen,
                        int[] chargeIds, int[] chargeGauges, int actorEntityId, boolean cramped,
                        int hintWidth,
                        Integer rosterWidth, Integer rosterHeight, Integer journalWidth, Integer journalBody,
                        RosterRowLines rosterLines, JournalEntryLines journalEntryLines,
                        JournalViewport.Anchor journalAnchor,
                        Integer journalChipWidth, JournalToggle journalToggle) {

        public Input {
            journalAnchor = journalAnchor == null ? JournalViewport.Anchor.NEWEST : journalAnchor;
        }

        /**
         * The same frame with the journal's chip column left at its default. Kept so a caller that does
         * not measure the chip (the tests, the probe) needs no chip width of its own.
         */
        public Input(int guiWidth, int guiHeight, double scale, Scale.Form form, int chatInputReserve,
                     boolean rosterVisible, int rosterCount, int rosterScroll,
                     JournalMode journalMode, boolean menuOpen,
                     int[] chargeIds, int[] chargeGauges, int actorEntityId, boolean cramped,
                     int hintWidth,
                     Integer rosterWidth, Integer rosterHeight, Integer journalWidth, Integer journalBody,
                     RosterRowLines rosterLines, JournalEntryLines journalEntryLines,
                     JournalViewport.Anchor journalAnchor) {
            this(guiWidth, guiHeight, scale, form, chatInputReserve, rosterVisible, rosterCount, rosterScroll,
                    journalMode, menuOpen, chargeIds, chargeGauges, actorEntityId, cramped, hintWidth, rosterWidth,
                    rosterHeight, journalWidth, journalBody, rosterLines, journalEntryLines, journalAnchor, null,
                    null);
        }

        /**
         * The journal's chip column in 100% design pixels: the widest chip label this frame really has to
         * draw, so an English category ("Damage") is never cut while the Chinese one leaves room unused.
         */
        public Input withJournalChip(int designWidth) {
            return new Input(guiWidth, guiHeight, scale, form, chatInputReserve, rosterVisible, rosterCount,
                    rosterScroll, journalMode, menuOpen, chargeIds, chargeGauges, actorEntityId, cramped, hintWidth,
                    rosterWidth, rosterHeight, journalWidth, journalBody, rosterLines, journalEntryLines,
                    journalAnchor, designWidth, journalToggle);
        }

        /** The same frame with the title row's own measured toggle, in 100% design pixels. */
        public Input withJournalToggle(JournalToggle toggle) {
            return new Input(guiWidth, guiHeight, scale, form, chatInputReserve, rosterVisible, rosterCount,
                    rosterScroll, journalMode, menuOpen, chargeIds, chargeGauges, actorEntityId, cramped, hintWidth,
                    rosterWidth, rosterHeight, journalWidth, journalBody, rosterLines, journalEntryLines,
                    journalAnchor, journalChipWidth, toggle);
        }

        /** The same frame with a different charge list, for tests and the rail's own checks. */
        public Input withCharge(int[] ids, int[] gauges, int actor) {
            return new Input(guiWidth, guiHeight, scale, form, chatInputReserve, rosterVisible, rosterCount,
                    rosterScroll, journalMode, menuOpen, ids, gauges, actor, cramped, hintWidth, rosterWidth,
                    rosterHeight, journalWidth, journalBody, rosterLines, journalEntryLines, journalAnchor,
                    journalChipWidth, journalToggle);
        }

        public Input withCramped(boolean value) {
            return new Input(guiWidth, guiHeight, scale, form, chatInputReserve, rosterVisible, rosterCount,
                    rosterScroll, journalMode, menuOpen, chargeIds, chargeGauges, actorEntityId, value, hintWidth,
                    rosterWidth, rosterHeight, journalWidth, journalBody, rosterLines, journalEntryLines,
                    journalAnchor, journalChipWidth, journalToggle);
        }

        public Input withHint(int width) {
            return new Input(guiWidth, guiHeight, scale, form, chatInputReserve, rosterVisible, rosterCount,
                    rosterScroll, journalMode, menuOpen, chargeIds, chargeGauges, actorEntityId, cramped, width,
                    rosterWidth, rosterHeight, journalWidth, journalBody, rosterLines, journalEntryLines,
                    journalAnchor, journalChipWidth, journalToggle);
        }

        /** The same frame browsed to a different journal position. */
        public Input withJournalAnchor(JournalViewport.Anchor anchor) {
            return new Input(guiWidth, guiHeight, scale, form, chatInputReserve, rosterVisible, rosterCount,
                    rosterScroll, journalMode, menuOpen, chargeIds, chargeGauges, actorEntityId, cramped, hintWidth,
                    rosterWidth, rosterHeight, journalWidth, journalBody, rosterLines, journalEntryLines, anchor,
                    journalChipWidth, journalToggle);
        }
    }

    private final Input input;
    private final double scale;
    private final boolean narrow;
    private final int bres;
    private final int bflo;

    private Rect rosterPanel = Rect.EMPTY;
    private Rect rosterTitle = Rect.EMPTY;
    private Rect rosterRule = Rect.EMPTY;
    private Rect rosterArea = Rect.EMPTY;
    private Rect rosterBar = Rect.EMPTY;
    private Rect rosterHandle = Rect.EMPTY;
    private Rect rosterHandleHit = Rect.EMPTY;
    private Rect rosterFooterRule = Rect.EMPTY;
    private Rect rosterFooterAp = Rect.EMPTY;
    private Rect rosterFooterWeapon = Rect.EMPTY;
    private int rosterRows;
    /** Where the drawn row window really starts: the requested offset, clamped to a filling window. */
    private int rosterStart;
    private boolean rosterScrollable;
    private int rosterWidth;
    private int rosterNameBudget;
    /** Name lines a row may use this frame, lowered when the list area is too short for three. */
    private int rosterNameLineCap = RosterRowText.MAX_NAME_LINES;
    private final List<Rect> rosterRowRects = new ArrayList<>();
    private int[] rosterLineCounts = new int[0];

    private Rect journalHeader = Rect.EMPTY;
    /** Union of the title strip and the body: the surface that owns camera gestures. */
    private Rect journalPanel = Rect.EMPTY;
    /**
     * The block of the one expanded panel that sits below the title strip (rule, body and footer);
     * empty while the body is inline. It is no longer a second painted surface.
     */
    private Rect journalFloat = Rect.EMPTY;
    private JournalMode journalMode = JournalMode.HIDDEN;
    private Rect journalTitle = Rect.EMPTY;
    private Rect journalToggle = Rect.EMPTY;
    private Rect journalRule = Rect.EMPTY;
    private Rect journalBody = Rect.EMPTY;
    private Rect journalBar = Rect.EMPTY;
    private Rect journalHandle = Rect.EMPTY;
    private Rect journalHandleHit = Rect.EMPTY;
    private Rect journalFooterRule = Rect.EMPTY;
    private Rect journalStatus = Rect.EMPTY;
    /** True when the title row had no room for the whole toggle label and drew the icon instead. */
    private boolean journalToggleIconOnly;
    private Rect journalCount = Rect.EMPTY;
    private int journalWidth;
    private int journalTextWidth;
    /**
     * The journal's chip column in effective GUI pixels: the widest chip label this frame draws, clamped
     * to the body. The painter draws exactly this width, so the text budget and the chip cannot drift.
     */
    private int journalChipWidth;
    private int journalBodyHeight;
    private final List<Rect> entryRows = new ArrayList<>();
    private final List<Integer> entryLines = new ArrayList<>();
    /** Every entry that intersects the body this frame, oldest first, partial ones included. */
    private final List<JournalRow> journalRows = new ArrayList<>();
    private int journalLines;
    /** The continuous pixel stack the body is a viewport over, and the view resolved from it. */
    private JournalViewport.Content journalContent = new JournalViewport.Content(null, null, 0, 1, 0);
    private JournalViewport.View journalView = JournalViewport.EMPTY;
    private JournalViewport.Anchor journalAnchor = JournalViewport.Anchor.NEWEST;
    private int journalFirst;
    private int journalDrawn;
    private int journalTotal;

    private Rect rail = Rect.EMPTY;
    private Rect railTrack = Rect.EMPTY;
    private Rect railTick = Rect.EMPTY;
    private Rect railSpacer = Rect.EMPTY;
    private final List<Portrait> portraits = new ArrayList<>();

    private Rect hint = Rect.EMPTY;

    private HudLayout(Input input) {
        this.input = input;
        this.scale = Scale.effective(input.scale());
        this.narrow = input.form() == Scale.Form.NARROW;
        // Neither panel reserves the chat history any more: the user asked for the left column to
        // reach over the chat messages and paint on top of them. Two things still bound it - the
        // screen edge with the hotbar strip that it may not cover, and, while a chat screen is open,
        // the edit line at the bottom that must stay usable.
        int hotbarAvoid = hotbarAvoid(input);
        int reserved = Math.max(hotbarAvoid, Math.max(0, input.chatInputReserve()));
        this.bres = input.guiHeight() - Scale.EDGE - reserved;
        // The floating body may cover the chat area only when no chat screen is open, which is the
        // same reservation: the edit line is part of the screen edge for the frame that shows it.
        this.bflo = input.guiHeight() - Scale.EDGE - reserved;
    }

    public static HudLayout compute(Input input) {
        HudLayout layout = new HudLayout(input);
        layout.placeRail();
        layout.computeRoster();
        layout.computeJournal();
        layout.computeHint();
        return layout;
    }

    /** {@code 40} when the left column reaches under the vanilla hotbar strip, otherwise 0. */
    private static int hotbarAvoid(Input input) {
        int left = Scale.EDGE;
        // The avoidance is decided on the default width: the default layout always keeps room for it.
        int right = Scale.EDGE + Scale.effectivePx(Scale.ROSTER_WIDTH, input.scale());
        int avoidLeft = input.guiWidth() / 2 - HOTBAR_AVOID_WIDTH / 2;
        int avoidRight = avoidLeft + HOTBAR_AVOID_WIDTH;
        return right > avoidLeft && left < avoidRight ? HOTBAR_AVOID_HEIGHT : 0;
    }

    private int px(double design) {
        return Scale.effectivePx(design, scale);
    }

    private int line() {
        return px(Motion.LINE);
    }

    // ------------------------------------------------------------------ widths

    /** The left edge of the right-anchored tactical panel, or a value past the screen when closed. */
    private int menuLeft() {
        if (!input.menuOpen()) return Integer.MAX_VALUE;
        return input.guiWidth() - Scale.EDGE - px(narrow ? Motion.PANEL_WIDTH_NARROW : Motion.PANEL_WIDTH);
    }

    /**
     * The saved width, clamped for this frame: at least the readable minimum, at most the larger of
     * 140 and whatever room is left before the nearest resident panel. Clamping is per frame only.
     */
    private int width(Integer saved, int neighbourLeft) {
        int wanted = px(saved == null ? Scale.ROSTER_WIDTH : saved);
        int min = px(ROSTER_MIN_WIDTH);
        int maxByScreen = (int) Math.floor(input.guiWidth() * 0.40D);
        int maxByPanel = neighbourLeft == Integer.MAX_VALUE ? Integer.MAX_VALUE : neighbourLeft - px(4) - Scale.EDGE;
        int max = Math.max(px(Scale.ROSTER_WIDTH),
                Math.min(Math.min(px(ROSTER_MAX_WIDTH), maxByScreen), maxByPanel));
        int clamped = Math.max(min, Math.min(max, wanted));
        // A window narrower than the readable minimum cannot be honoured; the minimum still wins so
        // the text never becomes unreadable, and the frame clip takes the overflow.
        return Math.max(1, Math.min(clamped, input.guiWidth() - Scale.EDGE * 2));
    }

    /** The nearest resident panel that vertically overlaps the left column: the rail, then the menu. */
    private int rosterNeighbourLeft() {
        int left = menuLeft();
        if (rail.w() > 0 && rail.x() > Scale.EDGE) left = Math.min(left, rail.x());
        return left;
    }

    // ------------------------------------------------------------------ roster

    private void computeRoster() {
        if (!input.rosterVisible() || input.rosterCount() <= 0) {
            rosterRows = 0;
            return;
        }
        int count = input.rosterCount();
        int pad = px(Motion.PAD);
        int width = width(input.rosterWidth(), rosterNeighbourLeft());
        rosterWidth = width;

        // The journal's minimum reservation decides how many rows the roster may use, so expanding
        // or collapsing the journal can never move the roster or its title row.
        int journalMin = input.form() == Scale.Form.STANDARD ? px(JOURNAL_MIN_STANDARD) : px(26);
        int fixed = pad + line() + px(TITLE_BLOCK) + pad;
        int footer = px(ROSTER_FOOTER);
        int budget = budgetBetween(bres, journalMin);
        if (input.form() != Scale.Form.STANDARD) {
            // The folded forms expand into a floating body, so the roster also has to leave room for
            // the title strip plus the smallest floating body above Bflo - otherwise an explicit
            // expand would have nothing to show and the roster would have to give a row back.
            int headerHeight = pad + px(JOURNAL_HEADER) + pad;
            int floating = bflo - Scale.EDGE - px(4) - headerHeight - px(2) - px(JOURNAL_FLOAT_MIN);
            budget = Math.min(budget, floating);
        }

        // The normal form first: every row the budget allows, footer included. When that leaves no
        // complete row at all the two footer lines are what took the room, so they are given back and
        // the space is divided again - the panel really shows one row instead of claiming one.
        Rows measured = measureRows(count, width, pad, fixed, footer, budget, true, false);
        if (measured.rows() <= 0) measured = measureRows(count, width, pad, fixed, footer, budget, false, false);
        if (measured.rows() <= 0 && bflo > bres) {
            // Last resort, and only here: the chat reserve is invisible space the HUD already hides
            // itself under, so the approved degradation lets this one panel cross Bres - never Bflo,
            // so never the hotbar, the screen edge or the journal's minimum reservation. Without it a
            // 115% preset in a 240-pixel-tall window cannot show a single row at all.
            int relaxed = budgetBetween(bflo, journalMin);
            if (input.form() != Scale.Form.STANDARD) {
                int headerHeight = pad + px(JOURNAL_HEADER) + pad;
                relaxed = Math.min(relaxed, bflo - Scale.EDGE - px(4) - headerHeight - px(2) - px(JOURNAL_FLOAT_MIN));
            }
            if (relaxed > budget) {
                Rows relaxedAttempt = measureRows(count, width, pad, fixed, footer, relaxed, true, false);
                if (relaxedAttempt.rows() <= 0) {
                    relaxedAttempt = measureRows(count, width, pad, fixed, footer, relaxed, false, false);
                }
                if (relaxedAttempt.rows() > 0) {
                    measured = relaxedAttempt;
                    budget = relaxed;
                }
            }
        }
        if (measured.scrollable() || measured.rows() <= 0) {
            // The scroll bar takes four pixels off the name column, and whether it exists depends on
            // the row heights, which depend on that column: measure once more with its final state.
            Rows again = measureRows(count, width, pad, fixed, footer, budget, measured.withFooter(),
                    measured.scrollable());
            if (again.rows() > 0 || again.rows() == measured.rows()) measured = again;
        }
        rosterNameLineCap = measured.lineCap();
        rosterLineCounts = measured.lines();
        rosterRows = measured.rows();
        rosterScrollable = measured.scrollable();
        rosterStart = measured.start();
        int area = measured.area();
        boolean withFooter = measured.withFooter();

        int height = fixed + area + (withFooter ? footer : 0);
        rosterPanel = Rect.of(Scale.EDGE, Scale.EDGE, width, height);
        int innerX = rosterPanel.x() + pad;
        int innerWidth = Math.max(1, width - pad * 2);
        int y = rosterPanel.y() + pad;
        rosterTitle = Rect.of(innerX, y, innerWidth, line());
        y += line() + px(4);
        rosterRule = Rect.of(innerX, y, innerWidth, Scale.rule(scale));
        y += rosterRule.h() + px(5);
        rosterArea = Rect.of(innerX, y, Math.max(1, rosterNameBudget + px(ROSTER_NAME_LEFT)), area);
        rosterBar = ScrollList.track(rosterArea, count, rosterRows, scale);
        y += area;
        if (withFooter) {
            y += px(4);
            rosterFooterRule = Rect.of(innerX, y, innerWidth, Scale.rule(scale));
            y += rosterFooterRule.h() + px(5);
            rosterFooterAp = Rect.of(innerX, y, innerWidth, line());
            y += line();
            // The handle's reserved corner takes eight pixels off the second footer line.
            rosterFooterWeapon = Rect.of(innerX, y, Math.max(1, innerWidth - px(ROSTER_HANDLE_RESERVE)), line());
        }
        rosterHandle = Rect.of(rosterPanel.right() - px(HANDLE_VISUAL) - 1, rosterPanel.bottom() - px(HANDLE_VISUAL) - 1,
                px(HANDLE_VISUAL), px(HANDLE_VISUAL));
        rosterHandleHit = handleHit(rosterPanel);
        rowRects(rosterArea, rosterRows, rosterStart, rosterLineCounts, count);
    }

    /** The vertical space the roster may use between a bottom bound and the journal's reservation. */
    private int budgetBetween(int limit, int journalMin) {
        return limit - Scale.EDGE - px(4) - journalMin;
    }

    private int rowHeight(int lines) {
        return px(ROSTER_ROW_BASE) + line() * Math.max(1, lines);
    }

    /**
     * One measurement of the roster list: row line counts, list height, how many rows fit, and whether
     * a scroll bar is needed.
     *
     * <p>One value instead of loose locals, so the two attempts - footer kept, and footer given back
     * to keep a row - can never mix numbers that were measured under different assumptions.</p>
     */
    private record Rows(int[] lines, int area, int rows, boolean scrollable, boolean withFooter, int lineCap,
                        int start) {
    }

    /**
     * Measures the list for one footer decision.
     *
     * @param withFooter whether the two footer lines are reserved; giving them up is the last resort
     *                   that buys the first row its room
     */
    private Rows measureRows(int count, int width, int pad, int fixed, int footer, int budget,
                             boolean withFooter, boolean scrollable) {
        int barReserve = scrollable ? px(ScrollList.BAR_WIDTH + ScrollList.BAR_GAP) : 0;
        int innerWidth = Math.max(1, width - pad * 2);
        rosterNameBudget = Math.max(1, innerWidth - px(ROSTER_NAME_LEFT) - px(2) - barReserve);
        int reserve = withFooter ? footer : 0;
        int room = Math.max(0, budget - fixed - reserve);
        int lineCap = nameLineCap(room);
        int[] lines = rowLines(count, rosterNameBudget, lineCap);
        int[] heights = new int[count];
        for (int index = 0; index < count; index++) heights[index] = rowHeight(lines[index]);
        int area = listHeight(heights, count, fixed, reserve, budget, input.rosterHeight());
        int[] fitting = fitRows(heights, count, input.rosterScroll(), area);
        return new Rows(lines, area, fitting[0], fitting[1] == 1, withFooter, lineCap, fitting[2]);
    }

    /**
     * How many name lines one row may use: the text lines the list area can really draw, minus the one
     * the information segment needs when it cannot join the last name line. Never fewer than one - and
     * never more than the approved three.
     */
    private int nameLineCap(int room) {
        int textLines = (room - px(ROSTER_ROW_BASE)) / Math.max(1, line());
        return Math.max(1, Math.min(RosterRowText.MAX_NAME_LINES, textLines - 1));
    }

    private int[] rowLines(int count, int nameBudget, int maxNameLines) {
        int[] lines = input.rosterLines() == null ? null : input.rosterLines().linesFor(nameBudget, maxNameLines);
        if (lines == null) lines = new int[0];
        int[] result = new int[count];
        for (int index = 0; index < count; index++) {
            result[index] = index < lines.length ? Math.max(1, Math.min(maxNameLines, lines[index])) : 1;
        }
        return result;
    }

    /**
     * The list's own height: every row it could show (never more than the budget), the saved height
     * when there is one, and never less than the tallest single row.
     */
    private int listHeight(int[] heights, int count, int fixed, int footer, int budget, Integer saved) {
        int content = 0;
        for (int index = 0; index < count; index++) content += heights[index] + px(ROSTER_ROW_GAP);
        if (count > 0) content -= px(ROSTER_ROW_GAP);
        int room = Math.max(0, budget - fixed - footer);
        int limit = saved == null ? room : Math.min(room, Math.max(0, px(saved) - fixed - footer));
        int tallest = 0;
        for (int index = 0; index < count; index++) tallest = Math.max(tallest, heights[index]);
        return Math.max(Math.min(content, limit), Math.min(tallest, room));
    }

    /**
     * The row window this frame draws: how many whole rows, whether anything stays hidden, and where
     * the window really starts.
     *
     * <p>The start is clamped against the deepest window that still fills the list area, measured from
     * the last member backwards. Measuring it the other way round - "how many rows fit from the
     * requested offset" - let a taller panel or a shorter list keep an offset nothing could scroll back
     * from: the first members were simply unreachable, and no bar was drawn because everything from
     * that offset fitted.</p>
     *
     * <p>"Scrollable" means <em>anything</em> is hidden, not "something is hidden below the window":
     * the second reading is false in the last window, which made the wheel drop a notch that should have
     * scrolled back up - the roster that would not scroll in the user's report.</p>
     */
    private int[] fitRows(int[] heights, int count, int offset, int area) {
        int gap = px(ROSTER_ROW_GAP);
        int tailRows = 0;
        int tailUsed = 0;
        for (int index = count - 1; index >= 0; index--) {
            int next = tailUsed + heights[index] + (tailRows == 0 ? 0 : gap);
            if (next > area) break;
            tailUsed = next;
            tailRows++;
        }
        int limit = Math.max(0, count - Math.max(1, tailRows));
        int start = Math.max(0, Math.min(limit, Math.max(0, offset)));
        int used = 0;
        int rows = 0;
        for (int index = start; index < count; index++) {
            int next = used + heights[index] + (rows == 0 ? 0 : gap);
            if (next > area) break;
            used = next;
            rows++;
        }
        return new int[]{rows, rows < count ? 1 : 0, start};
    }

    /** Row rectangles from the scroll offset, in the order they are drawn. */
    private void rowRects(Rect area, int rows, int offset, int[] lines, int count) {
        rosterRowRects.clear();
        int y = area.y();
        int gap = px(ROSTER_ROW_GAP);
        for (int slot = 0; slot < rows; slot++) {
            int index = offset + slot;
            if (index >= count) break;
            int height = rowHeight(lines[index]);
            rosterRowRects.add(Rect.of(area.x(), y, area.w(), height));
            y += height + gap;
        }
    }

    /** The rectangle of the visible row at {@code slot}, in row order. */
    public Rect rosterRow(int slot) {
        if (slot < 0 || slot >= rosterRowRects.size()) return Rect.EMPTY;
        return rosterRowRects.get(slot);
    }

    /** Height of the visible row at {@code slot}: rows differ once names wrap. */
    public int rosterRowHeight(int slot) {
        Rect row = rosterRow(slot);
        return row.h() <= 0 ? px(ROSTER_ROW_BASE) + line() : row.h();
    }

    public int rosterRowHeight() {
        return px(ROSTER_ROW_BASE) + line();
    }

    /** Name-column width this frame: everything the wrapping was measured against. */
    public int rosterNameBudget() {
        return rosterNameBudget;
    }

    /**
     * Name lines a row may use this frame. The painter reads the same cap the measurement used, so a
     * shortened row is drawn with the wrapping the layout reserved for it.
     */
    public int rosterNameLineCap() {
        return rosterNameLineCap;
    }

    public int rosterWidth() {
        return rosterWidth;
    }

    /** Text lines of the row at {@code slot}, so the painter can lay out the same wrapping. */
    public int rosterLines(int slot) {
        int index = rosterStart + slot;
        return index >= 0 && index < rosterLineCounts.length ? rosterLineCounts[index] : 1;
    }

    // ------------------------------------------------------------------ journal

    /**
     * The journal has two shapes. In the standard form the body is part of the same panel: title row,
     * accent rule, entries, separator and footer. In the tight and narrow forms the title row keeps
     * its strip and the body plus footer become a floating opaque surface two pixels below it, so
     * expanding never pushes the roster or the title row down.
     *
     * <p>The body is exactly as tall as its own saved height, clamped for this frame: entries no
     * longer decide the panel's height, which is what keeps the panel from jumping while scrolling.</p>
     */
    private void computeJournal() {
        JournalMode mode = input.journalMode();
        journalMode = mode;
        if (mode == JournalMode.HIDDEN) return;
        int pad = px(Motion.PAD);
        int width = width(input.journalWidth(), menuLeft());
        journalWidth = width;
        int headerHeight = pad + px(JOURNAL_HEADER) + pad;
        int top = rosterPanel.h() <= 0 ? Scale.EDGE : rosterPanel.bottom() + px(4);
        // In the last-resort degradation the strip follows the roster and may cross Bres, but never
        // Bflo, and the floating body below it still fits at least one complete entry.
        if (top + headerHeight > bres && rosterRows > 0) {
            top = Math.max(Scale.EDGE, Math.min(top, bflo - headerHeight));
        }
        journalHeader = Rect.of(Scale.EDGE, top, width, headerHeight);
        int innerX = journalHeader.x() + pad;
        int innerWidth = Math.max(1, width - pad * 2);
        // The toggle gives up eight pixels for the handle's reserved corner, in both states. Its width is
        // the real label's, and when the title plus that label cannot sit side by side it becomes the
        // 14-pixel icon button instead - the title is never the part that gets cut.
        JournalToggle toggleInput = input.journalToggle();
        int toggleWidth = Math.min(innerWidth, px(JOURNAL_TOGGLE_WIDTH));
        boolean iconOnly = false;
        if (toggleInput != null) {
            // Two pixels of slack: rounding the label's width and the padding separately can otherwise
            // pick the whole label and then cut it by a pixel in the button itself.
            int full = px(toggleInput.labelWidth() + Motion.BUTTON_PAD_X * 2 + 2);
            int icon = px(toggleInput.iconWidth() + Motion.BUTTON_PAD_X * 2);
            int titleNeed = px(toggleInput.titleWidth());
            boolean fits = titleNeed + px(4) + full <= innerWidth - px(JOURNAL_HANDLE_RESERVE);
            toggleWidth = Math.min(innerWidth, fits ? full : icon);
            iconOnly = !fits;
        }
        journalToggleIconOnly = iconOnly;
        int toggleRight = innerX + innerWidth - px(JOURNAL_HANDLE_RESERVE);
        journalToggle = Rect.of(toggleRight - toggleWidth, top + pad, toggleWidth, px(JOURNAL_HEADER));
        journalTitle = Rect.of(innerX, top + pad, Math.max(1, toggleRight - toggleWidth - px(2) - innerX),
                px(JOURNAL_HEADER));
        journalHandle = Rect.of(journalHeader.right() - px(HANDLE_VISUAL) - 1,
                journalHeader.bottom() - px(HANDLE_VISUAL) - 1, px(HANDLE_VISUAL), px(HANDLE_VISUAL));
        journalHandleHit = handleHit(journalHeader);
        if (mode == JournalMode.HEADER) {
            journalPanel = journalHeader;
            return;
        }

        boolean floating = mode == JournalMode.FLOATING;
        int ruleTop = floating ? journalHeader.bottom() + px(2) : journalHeader.bottom() + px(4);
        journalRule = Rect.of(innerX, ruleTop, innerWidth, Scale.rule(scale));
        int bodyTop = journalRule.bottom() + px(5);
        int limit = floating ? bflo : bres;
        int nonBody = px(FOOTER_BLOCK) + pad;
        // The body is as tall as its preference unless the window is shorter: the boundary always wins
        // over the readable floor, so a tiny window never pushes the footer past the hotbar.
        int available = Math.max(1, limit - bodyTop - nonBody);
        int bodyMax = Math.max(1, available);
        int wanted = px(input.journalBody() == null ? JOURNAL_BODY_DEFAULT : input.journalBody());
        int bodyHeight = Math.min(bodyMax, Math.max(Math.min(px(JOURNAL_BODY_MIN), bodyMax), wanted));
        journalBodyHeight = bodyHeight;
        journalBody = Rect.of(innerX, bodyTop, Math.max(1, innerWidth), bodyHeight);
        // The chip column is the same in both states; the bar only takes its own width away from the
        // text while the body really scrolls, so a wrapped line never runs under the track. Its width is
        // the widest chip label the frame has to draw (measured by the painter, passed in), clamped so a
        // text column always remains: one value for the text budget and for the chip itself.
        int chipDesign = input.journalChipWidth() == null ? JOURNAL_CHIP_WIDTH : input.journalChipWidth();
        int chipColumn = Math.min(Math.max(1, px(chipDesign)),
                Math.max(1, journalBody.w() - px(JOURNAL_CHIP_MIN_TEXT)));
        journalChipWidth = Math.max(1, chipColumn);
        int textWidth = Math.max(1, journalBody.w() - journalChipWidth - px(2));
        int rowHeight = line();
        int gap = px(JOURNAL_ENTRY_GAP);
        JournalMeasure measure = measureJournal(textWidth);
        JournalViewport.Content content = stack(measure, rowHeight, gap, bodyHeight);
        if (content.scrollable()) {
            textWidth = Math.max(1, textWidth - px(ScrollList.BAR_WIDTH + ScrollList.BAR_GAP));
            measure = measureJournal(textWidth);
            content = stack(measure, rowHeight, gap, bodyHeight);
        }
        journalTextWidth = textWidth;
        journalContent = content;
        // One contract decides what is drawn. The whole retained history is the stack, the body is a
        // viewport over it, and the anchor names the entry under the body's top edge - so the rows, the
        // bar, the footer count and the probe all describe the same frame, and an entry that does not
        // fit at an edge is drawn as far as it reaches instead of being dropped whole.
        int scrollTop = JournalViewport.scrollTop(content, input.journalAnchor());
        journalView = JournalViewport.visible(content, scrollTop);
        journalAnchor = JournalViewport.anchorAt(content, journalView.scrollTop());
        journalTotal = content.count();
        buildJournalRows(content, measure, bodyTop);
        journalFirst = journalView.empty() ? 0 : journalView.first();
        journalDrawn = journalRows.size();
        journalBar = ScrollList.track(journalBody, journalView.total(), journalView.bodyHeight(), scale);

        // The footer sits under the whole body, not under the last drawn entry, so it never moves.
        int footerRuleTop = bodyTop + bodyHeight + px(4);
        journalFooterRule = Rect.of(innerX, footerRuleTop, innerWidth, Scale.rule(scale));
        int footerY = journalFooterRule.bottom() + px(5);
        journalStatus = Rect.of(innerX, footerY, Math.max(1, innerWidth), line());
        // The count keeps its own right edge clear of the handle's reserved corner.
        int countWidth = Math.max(1, innerWidth - px(JOURNAL_HANDLE_RESERVE + 6));
        journalCount = Rect.of(innerX + innerWidth - countWidth - px(JOURNAL_HANDLE_RESERVE), footerY,
                countWidth, line());
        int bottom = footerY + line() + pad;

        if (floating) {
            // The tight and narrow forms used to draw the title strip and a second surface below it,
            // which the user saw as "two windows" above 100%. The expanded journal is now one window in
            // every form: the panel runs from the title row's top edge to the footer's bottom edge, and
            // only the body block below the strip is remembered separately (for the accent rule, the
            // handle and the hit tests that used to live on that second surface).
            journalFloat = Rect.of(journalRule.x() - pad, journalRule.y(), width, bottom - journalRule.y());
            journalPanel = Rect.of(Scale.EDGE, top, width, bottom - top);
        } else {
            journalPanel = Rect.of(Scale.EDGE, top, width, bottom - top);
        }
        // An expanded journal carries exactly one handle, in the bottom-right corner of the one surface
        // it is drawn on. Leaving it on the title strip made the grip hang next to the "收起" button of
        // a window it no longer belonged to, and dragging there resized the journal from the wrong corner.
        Rect handleBase = journalPanel;
        journalHandle = Rect.of(handleBase.right() - px(HANDLE_VISUAL) - 1,
                handleBase.bottom() - px(HANDLE_VISUAL) - 1, px(HANDLE_VISUAL), px(HANDLE_VISUAL));
        journalHandleHit = handleHit(handleBase);
    }

    private JournalMeasure measureJournal(int textWidth) {
        JournalMeasure measure = input.journalEntryLines() == null ? null
                : input.journalEntryLines().linesFor(textWidth);
        if (measure == null) return new JournalMeasure(new int[0], new long[0]);
        return new JournalMeasure(measure.linesNewestFirst() == null ? new int[0] : measure.linesNewestFirst(),
                measure.sequencesOldestFirst() == null ? new long[0] : measure.sequencesOldestFirst());
    }

    /**
     * The retained history as a continuous pixel stack, oldest first: every entry keeps its own real
     * height, so nothing is stretched or shortened to make a boundary look tidy.
     */
    private JournalViewport.Content stack(JournalMeasure measure, int rowHeight, int gap, int bodyHeight) {
        int[] lines = measure.linesNewestFirst();
        long[] sequences = measure.sequencesOldestFirst();
        int count = Math.min(lines.length, sequences.length);
        int[] heights = new int[count];
        long[] ordered = new long[count];
        for (int index = 0; index < count; index++) {
            ordered[index] = sequences[index];
            heights[index] = Math.max(1, lines[count - 1 - index]) * rowHeight;
        }
        return new JournalViewport.Content(ordered, heights, gap, rowHeight, bodyHeight);
    }

    /**
     * Every entry that intersects the body, oldest first.
     *
     * <p>A row carries two rectangles: {@code draw} is where its text is painted - it may start above
     * the body or end below it, which is exactly the part entry the user asked for - and
     * {@code visible} is the part inside the body, which is what the pointer and the probe use so an
     * offscreen row can never cover the title strip or the footer.</p>
     */
    private void buildJournalRows(JournalViewport.Content content, JournalMeasure measure, int bodyTop) {
        entryRows.clear();
        entryLines.clear();
        journalRows.clear();
        journalLines = 0;
        if (journalView.empty()) return;
        int[] lines = measure.linesNewestFirst();
        int bodyBottom = journalBody.bottom();
        // The stack's top edge: the newest end of the history always touches the body's bottom edge, so
        // a body that is not full keeps its blank space above the log - the console order the user
        // asked for - and a full one starts at the body's top edge and is cut by the scissor.
        int contentTop = bodyBottom - journalView.total() + journalView.offsetPx();
        for (int index = journalView.first(); index <= journalView.last(); index++) {
            int height = content.height(index);
            int y = contentTop + content.top(index);
            Rect draw = Rect.of(journalBody.x(), y, journalBody.w(), height);
            Rect visible = draw.intersection(journalBody);
            if (visible.h() <= 0) continue;
            int lineCount = Math.max(1, lines.length > content.count() - 1 - index
                    ? lines[content.count() - 1 - index] : 1);
            journalRows.add(new JournalRow(index, draw, visible, lineCount, y < bodyTop, y + height > bodyBottom));
            entryRows.add(draw);
            entryLines.add(lineCount);
            journalLines += lineCount;
        }
    }

    /** Total body height preference this frame, for the drag tag and the probe. */
    public int journalBodyHeight() {
        return journalBodyHeight;
    }

    public int journalWidth() {
        return journalWidth;
    }

    /**
     * The chip column's width in effective GUI pixels, measured from this frame's real labels. Zero while
     * the journal has no body; the painter then falls back to the default width.
     */
    public int journalChipWidth() {
        return journalChipWidth;
    }

    public int journalTextWidth() {
        return journalTextWidth;
    }

    // ------------------------------------------------------------------ handles

    /**
     * Every rectangle of this frame that carries a number or a line of text.
     *
     * <p>The size tag of a drag is drawn on top of the HUD, so it has to be placed around these: the
     * roster's AP and weapon footer lines, the journal's status and count, and every row text of both
     * panels. Keeping the list here means the placement and the acceptance probe read the same
     * rectangles the painting used.</p>
     */
    public List<Rect> numberRects() {
        List<Rect> rects = new ArrayList<>();
        addIfVisible(rects, rosterFooterAp);
        addIfVisible(rects, rosterFooterWeapon);
        addIfVisible(rects, journalStatus);
        addIfVisible(rects, journalCount);
        rects.addAll(rosterRowRects);
        // The part of an entry the body really shows, never a partial row's offscreen remainder: a
        // size tag must not be pushed away by text hidden above the body or below it.
        for (JournalRow row : journalRows) addIfVisible(rects, row.visible());
        return rects;
    }

    private static void addIfVisible(List<Rect> rects, Rect rect) {
        if (rect != null && rect.w() > 0 && rect.h() > 0) rects.add(rect);
    }

    /** The 12×12 hit area of a handle: the reserved corner grown outwards, inside the panel edges. */
    private Rect handleHit(Rect panel) {
        int hit = px(HANDLE_HIT);
        int right = panel.right();
        int bottom = panel.bottom();
        return Rect.of(right - hit, bottom - hit, hit, hit);
    }

    // ------------------------------------------------------------------ rail

    /**
     * The charge rail is placed on the *default* panel width, exactly as the specification says: the
     * form decision and the rail must not move when the user drags a panel wider or narrower.
     */
    private void placeRail() {
        int[] ids = input.chargeIds() == null ? new int[0] : input.chargeIds();
        if (ids.length == 0) return;
        boolean wide = input.form() == Scale.Form.STANDARD;
        int width = px(wide ? RAIL_WIDTH_STANDARD : Scale.RAIL_WIDTH);
        int height = px(RAIL_HEIGHT);
        int x;
        if (wide) {
            x = (input.guiWidth() - width) / 2;
        } else {
            int rosterRight = Scale.EDGE + px(Scale.ROSTER_WIDTH);
            int panelWidth = px(narrow ? Motion.PANEL_WIDTH_NARROW : Motion.PANEL_WIDTH);
            int panelLeft = input.guiWidth() - Scale.EDGE - panelWidth;
            int gap = Math.max(0, panelLeft - rosterRight);
            int minGap = px(narrow ? 8 : 4);
            int usable = Math.max(1, Math.min(width, gap - minGap * 2));
            width = Math.min(width, Math.max(px(PORTRAIT), usable));
            x = rosterRight + Math.max(minGap, (gap - width) / 2);
        }
        rail = Rect.of(x, Scale.EDGE, width, height);
        int inset = px(RAIL_INSET);
        railTrack = Rect.of(rail.x() + inset, rail.y() + px(RAIL_TRACK_Y),
                Math.max(1, width - inset * 2), Scale.rule(scale));
        railTick = Rect.of(rail.right() - inset - Scale.rule(scale), railTrack.y() - px(RAIL_END_TICK) / 2,
                Scale.rule(scale), px(RAIL_END_TICK));
        railSpacer = rail;

        int[] gauges = input.chargeGauges() == null ? new int[0] : input.chargeGauges();
        int actor = input.actorEntityId();
        boolean onlyCurrent = input.cramped();
        List<double[]> placed = new ArrayList<>();
        for (int index = 0; index < ids.length; index++) {
            boolean current = ids[index] == actor;
            if (onlyCurrent && !current) continue;
            int size = px(current ? PORTRAIT_CURRENT : PORTRAIT);
            double fraction = current ? 1.0D
                    : Math.max(0.0D, Math.min(1.0D, (index < gauges.length ? gauges[index] : 0) / 100.0D));
            double left = (width - size) * fraction;
            int lane = 0;
            boolean occupied;
            do {
                occupied = false;
                for (double[] other : placed) {
                    if ((int) other[2] == lane && Math.abs(other[0] - left) < size + 3) {
                        lane++;
                        occupied = true;
                        break;
                    }
                }
            } while (occupied && lane < 8);
            placed.add(new double[]{left, size, lane});
            int top = rail.y() + px(current ? RAIL_CURRENT_Y : lane == 0 ? RAIL_LANE0_Y : RAIL_LANE1_Y);
            portraits.add(new Portrait(index, ids[index],
                    Rect.of((int) Math.round(rail.x() + left), top, size, size), lane, current));
        }
    }

    // ------------------------------------------------------------------ hint

    private void computeHint() {
        int width = Math.max(0, input.hintWidth());
        if (width <= 0) return;
        int height = line() + px(6);
        hint = Rect.of((input.guiWidth() - width) / 2, Scale.EDGE, width, height);
    }

    // ------------------------------------------------------------------ queries

    public Input input() {
        return input;
    }

    public double scale() {
        return scale;
    }

    public boolean narrow() {
        return narrow;
    }

    public int bres() {
        return bres;
    }

    public int bflo() {
        return bflo;
    }

    public Rect rosterPanel() {
        return rosterPanel;
    }

    public Rect rosterTitle() {
        return rosterTitle;
    }

    public Rect rosterRule() {
        return rosterRule;
    }

    public Rect rosterArea() {
        return rosterArea;
    }

    public Rect rosterBar() {
        return rosterBar;
    }

    public Rect rosterHandle() {
        return rosterHandle;
    }

    public Rect rosterHandleHit() {
        return rosterHandleHit;
    }

    public Rect rosterFooterRule() {
        return rosterFooterRule;
    }

    public Rect rosterFooterAp() {
        return rosterFooterAp;
    }

    public Rect rosterFooterWeapon() {
        return rosterFooterWeapon;
    }

    public int rosterRows() {
        return rosterRows;
    }

    /**
     * Where the drawn row window starts, after the clamp described at {@link #fitRows}. The caller
     * adopts it as its own scroll position, so state and pixels can never describe two windows.
     */
    public int rosterStart() {
        return rosterStart;
    }

    public boolean rosterScrollable() {
        return rosterScrollable;
    }

    public Rect journalHeader() {
        return journalHeader;
    }

    public Rect journalPanel() {
        return journalPanel;
    }

    public Rect journalFloat() {
        return journalFloat;
    }

    /**
     * True when the body of a folded form is explicitly expanded: that window is an opaque {@code
     * FLOAT} surface, because it may cover other tactical panels.
     */
    public boolean journalBodyFloats() {
        return journalFloat.w() > 0;
    }

    public Rect journalBase() {
        return journalMode == JournalMode.HEADER ? journalHeader : journalPanel;
    }

    public Rect journalTitle() {
        return journalTitle;
    }

    public Rect journalToggle() {
        return journalToggle;
    }

    /** True when the toggle is the 14-pixel icon button, i.e. the full label only lives in a tooltip. */
    public boolean journalToggleIconOnly() {
        return journalToggleIconOnly;
    }

    public Rect journalHandle() {
        return journalHandle;
    }

    public Rect journalHandleHit() {
        return journalHandleHit;
    }

    public Rect journalRule() {
        return journalRule;
    }

    public Rect journalBody() {
        return journalBody;
    }

    public Rect journalBar() {
        return journalBar;
    }

    public Rect journalFooterRule() {
        return journalFooterRule;
    }

    public Rect journalStatus() {
        return journalStatus;
    }

    public Rect journalCount() {
        return journalCount;
    }

    public List<Rect> entryRows() {
        return entryRows;
    }

    /** Line counts of {@link #entryRows()}, in the same oldest-first order the entries are drawn in. */
    public List<Integer> entryLineCounts() {
        return entryLines;
    }

    public int journalLines() {
        return journalLines;
    }

    /**
     * One drawn journal entry: where its text goes, the part of it the body really shows, and whether
     * the scissor cuts it at the body's top or bottom edge.
     */
    public record JournalRow(int index, Rect draw, Rect visible, int lines, boolean clippedTop,
                             boolean clippedBottom) {
    }

    /** Every entry that intersects the body this frame, oldest first. */
    public List<JournalRow> journalRows() {
        return journalRows;
    }

    /** The continuous pixel stack the body is a viewport over, for the wheel and the probe. */
    public JournalViewport.Content journalContent() {
        return journalContent;
    }

    /** The view resolved from that stack this frame. */
    public JournalViewport.View journalView() {
        return journalView;
    }

    /** Where the body is browsed to after clamping; the state adopts it so both agree. */
    public JournalViewport.Anchor journalAnchor() {
        return journalAnchor;
    }

    /** Pixels scrolled from the newest end: 0 means the newest entry hugs the body's bottom edge. */
    public int journalOffsetPx() {
        return journalView.offsetPx();
    }

    /** Furthest the retained stack can be scrolled: the bar's own range, in pixels. */
    public int journalMaxScrollPx() {
        return journalView.maxScroll();
    }

    /** The whole retained stack's height in pixels: the bar's total. */
    public int journalTotalPx() {
        return journalView.total();
    }

    /** Index into the retained history, oldest first, of the oldest entry that was drawn. */
    public int journalFirstIndex() {
        return journalFirst;
    }

    /** Entries the body really draws this frame, counted with the partial ones at either edge. */
    public int journalDrawn() {
        return journalDrawn;
    }

    /** Total entries in the retained history: the bar's total in entries, never a lifetime sequence. */
    public int journalTotal() {
        return journalTotal;
    }

    public JournalMode journalMode() {
        return journalMode;
    }

    public Rect rail() {
        return rail;
    }

    public Rect railTrack() {
        return railTrack;
    }

    public Rect railTick() {
        return railTick;
    }

    public List<Portrait> portraits() {
        return portraits;
    }

    public Rect hint() {
        return hint;
    }

    /** Text-column width the journal wraps against, for a caller that measures before laying out. */
    public static int journalTextWidth(double scale) {
        return Math.max(1, journalInnerWidth(scale) - Scale.effectivePx(JOURNAL_CHIP_WIDTH + 2, scale));
    }

    public static int journalInnerWidth(double scale) {
        return Math.max(1, Scale.effectivePx(Scale.ROSTER_WIDTH, scale) - Scale.effectivePx(Motion.PAD, scale) * 2);
    }

    /**
     * True when the pointer is over a surface that owns camera gestures.
     *
     * <p>The charge rail and the portraits on it are part of the HUD, exactly like the two panels:
     * leaving them out meant the top portraits answered no hit at all, so hovering one never reached
     * the entity locator and the camera never moved. The journal's folded early return now happens
     * after those checks, never before them.</p>
     */
    public boolean overUi(double x, double y) {
        if (rosterPanel.w() > 0 && rosterPanel.contains(x, y)) return true;
        if (rail.w() > 0 && rail.contains(x, y)) return true;
        // A portrait may stick out of the rail rectangle (the taller current-actor frame, the lower
        // lane): the hit follows the drawn box, not the band behind it.
        for (Portrait portrait : portraits) {
            if (portrait.box().w() > 0 && portrait.box().contains(x, y)) return true;
        }
        if (journalMode == JournalMode.HIDDEN) return false;
        if (journalMode == JournalMode.HEADER) return journalHeader.contains(x, y);
        // One continuous window in every expanded form: no seam between the title strip and the body
        // may fall through to the battlefield, which the old strip-plus-float test did on its gap.
        return journalPanel.contains(x, y);
    }

    /** The portrait under the pointer, topmost lane first; null when none is hit. */
    public Portrait portraitAt(double x, double y) {
        Portrait hit = null;
        for (Portrait portrait : portraits) {
            if (!portrait.box().contains(x, y)) continue;
            if (hit == null || portrait.lane() >= hit.lane() || portrait.current()) hit = portrait;
        }
        return hit;
    }

    /** The journal entry row under the pointer, or -1. Only the part inside the body can be hit. */
    public int entryAt(double x, double y) {
        for (int index = 0; index < journalRows.size(); index++) {
            if (journalRows.get(index).visible().contains(x, y)) return index;
        }
        return -1;
    }

    /** Entries the journal body really shows, partial ones included. */
    public int journalVisibleRows() {
        return journalRows.size();
    }

    /** One portrait on the rail: the charge list index, its box, its lane and whether it acts now. */
    public record Portrait(int index, int entityId, Rect box, int lane, boolean current) {
    }

    /** Empty bottom space of a row list, drawn as SLAB so a short list never shows a half row. */
    public Rect rosterBlankTail() {
        int used = 0;
        for (Rect row : rosterRowRects) used = Math.max(used, row.bottom() - rosterArea.y());
        int left = rosterArea.h() - used;
        if (left <= 0) return Rect.EMPTY;
        return Rect.of(rosterArea.x(), rosterArea.y() + used, rosterArea.w(), left);
    }
}
