package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.neoforge.client.ui.panel.NativePanelScreen;
import com.tactical.neoforge.client.ui.panel.PanelLayout;
import com.tactical.neoforge.client.ui.platform.Draw;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Slate;
import com.tactical.neoforge.network.MoveChoiceOfferPacket;
import com.tactical.neoforge.network.MoveChoicePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Both viable routes are explicit choices; the server revalidates the nonce and collision.
 *
 * <p>The offer only exists when a detour and a drop are both possible, so this page always has
 * exactly two rows and never scrolls. The stated cost is the server's, and the warning is the one
 * sentence that mattered before: a long drop may hurt.</p>
 */
public final class MoveChoiceScreen extends NativePanelScreen {

    public static final String SCREEN_ID = "move_choice";
    public static final String TARGET = "route-target";
    public static final String WALK = "route-walk";
    public static final String DROP = "route-drop";
    public static final String WARNING = "route-warning";
    public static final String BACK = "route-cancel";

    /** A route row carries no description line, so every form uses the short row. */
    private static final double ROW_HEIGHT = 18;
    private static final int MAX_WARNING_LINES = 3;

    private final MoveChoiceOfferPacket offer;
    private String targetFull = "";
    private String warningFull = "";
    /** Which lines this frame really had to shorten, so only those offer a tooltip. */
    private boolean targetShortened;
    private boolean walkShortened;
    private boolean dropShortened;
    private boolean backShortened;
    private String walkFull = "";
    private String dropFull = "";

    private MoveChoiceScreen(MoveChoiceOfferPacket offer) {
        this.offer = offer;
    }

    public static void open(MoveChoiceOfferPacket offer) {
        if (!BattleClientState.isPlayerTurn() || BattleClientState.moving) return;
        Minecraft.getInstance().setScreen(new MoveChoiceScreen(offer));
    }

    /** Static pages need no rebinding now; kept for the existing tick hook. */
    public static void tickOpenScreen() {
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
        layout.block(TARGET, Motion.LINE);
        layout.gap(Motion.GROUP_GAP_ABOVE);
        layout.interactive(WALK, layout.block(WALK, ROW_HEIGHT));
        layout.gap(Motion.ROW_GAP);
        layout.interactive(DROP, layout.block(DROP, ROW_HEIGHT));
        layout.gap(Motion.GROUP_GAP_ABOVE);
        int lines = warningLines(layout);
        layout.block(WARNING, lines * Motion.LINE);
        int backWidth = layout.px(buttonDesignWidth(ClientText.get(ClientText.Keys.MOVE_BACK)));
        layout.footer(List.of(BACK), List.of(backWidth), Motion.BUTTON_HEIGHT);
        return layout.finish();
    }

    /** The warning folds to the row width; the last of three lines may be cut with an ellipsis. */
    private int warningLines(PanelLayout layout) {
        String warning = warningText();
        int width = Math.max(1, layout.innerWidth() - layout.px(6));
        int lines = com.tactical.neoforge.client.ui.layout.TextMetrics
                .wrap(warning, width, layout.scale(), Minecraft.getInstance().font::width).size();
        return Math.max(1, Math.min(MAX_WARNING_LINES, lines));
    }

    private String warningText() {
        return ClientText.get(offer.dropHeight() > 3
                ? ClientText.Keys.MOVE_WARNING_FALL : ClientText.Keys.MOVE_WARNING_BLOCKED);
    }

    @Override
    protected void drawBody(GuiGraphics graphics, Font font, double scale, double alpha, long now, double delta) {
        drawTitle(graphics, font, scale, ClientText.get(ClientText.Keys.MOVE_TITLE), alpha);
        drawRule(graphics);

        Rect target = layout.rect(TARGET);
        targetFull = ClientText.get(ClientText.Keys.MOVE_TARGET, offer.target().toString(),
                Integer.toString(offer.dropHeight()));
        String targetShown = ellipsis(font, targetFull, target.w());
        targetShortened = !targetShown.equals(targetFull);
        Draw.scaledText(graphics, font, targetShown, target.x(), baseline(target, scale),
                scale, alpha(Slate.TEXT, alpha));

        walkFull = ClientText.get(ClientText.Keys.MOVE_WALK);
        dropFull = ClientText.get(ClientText.Keys.MOVE_DROP);
        walkShortened = routeRow(graphics, font, scale, WALK, walkFull, offer.walkCost(), alpha, now, delta);
        dropShortened = routeRow(graphics, font, scale, DROP, dropFull, offer.dropCost(), alpha, now, delta);
        drawWarning(graphics, font, scale, alpha);
        // A single footer button is clamped to the panel's inner width by the layout, so the label can be
        // shortened here too; the whole wording then hangs on the button's own tooltip.
        Rect back = layout.rect(BACK);
        String backFull = ClientText.get(ClientText.Keys.MOVE_BACK);
        int backPad = layout.px(Motion.BUTTON_PAD_X) * 2;
        backShortened = textWidth(font, backFull) > Math.max(1, back.w() - backPad);
        button(graphics, font, scale, BACK, backFull, true, alpha, now, delta);
    }

    /** One route: the shared row surface, the movement icon, the name and the server's price. */
    private boolean routeRow(GuiGraphics graphics, Font font, double scale, String id, String name, int cost,
                             double alpha, long now, double delta) {
        Rect rect = layout.rect(id);
        Rect box = rowSurface(graphics, id, rect, true, alpha, now, delta);
        Rect well = Rect.of(box.x() + layout.px(Motion.ICON_BOX_INSET), box.y() + layout.px(1),
                layout.px(Motion.ICON), layout.px(Motion.ICON));
        Draw.fill(graphics, well, alpha(id.equals(hoveredId) ? UiPalette.accentDark() : Slate.SUNK, alpha));
        item(graphics, new ItemStack(Items.IRON_BOOTS), well);
        int textX = box.x() + layout.px(narrow() ? Motion.ICON_TEXT_LEFT_NARROW : Motion.ICON_TEXT_LEFT);
        int y = box.y() + Math.max(0, (box.h() - layout.px(Motion.LINE)) / 2);
        String price = cost + " AP";
        int priceWidth = textWidth(font, price);
        String nameShown = ellipsis(font, name, Math.max(1, box.right() - layout.px(4) - textX
                - priceWidth - layout.px(4)));
        Draw.scaledText(graphics, font, nameShown, textX, y, scale, alpha(Slate.TEXT, alpha));
        Draw.scaledText(graphics, font, price, box.right() - layout.px(4) - priceWidth, y, scale,
                alpha(Slate.AP, alpha));
        return !nameShown.equals(name);
    }

    /** The warning is the one block of prose this page may wrap, and it never grows past three lines. */
    private void drawWarning(GuiGraphics graphics, Font font, double scale, double alpha) {
        Rect block = layout.rect(WARNING);
        if (block.w() <= 0) return;
        warningFull = warningText();
        boolean dangerous = offer.dropHeight() > 3;
        int barWidth = layout.px(2);
        Draw.fill(graphics, block.x(), block.y(), barWidth, block.h(), alpha(dangerous ? Slate.HP : Slate.LINE, alpha));
        int textX = block.x() + layout.px(6);
        int width = Math.max(1, block.right() - textX);
        List<String> lines = wrap(font, warningFull, width);
        int limit = Math.min(MAX_WARNING_LINES, lines.size());
        for (int index = 0; index < limit; index++) {
            String line = lines.get(index);
            if (index == limit - 1 && lines.size() > MAX_WARNING_LINES) line = line + "…";
            Draw.scaledText(graphics, font, line, textX, block.y() + index * layout.px(Motion.LINE), scale,
                    alpha(dangerous ? Slate.HP : Slate.TEXT2, alpha));
        }
    }

    @Override
    protected void activate(String id) {
        switch (id) {
            case WALK -> send(0);
            case DROP -> send(1);
            case BACK -> cancel(offer);
            default -> {
            }
        }
    }

    private void send(int mode) {
        var stamp = BattleClientState.stamp();
        if (stamp == null) return;
        PacketDistributor.sendToServer(new MoveChoicePacket(stamp, offer.token(), mode));
        Minecraft.getInstance().setScreen(null);
    }

    /** Cancelling tells the server the offer is refused, then returns to the aiming page. */
    private static void cancel(MoveChoiceOfferPacket offer) {
        var stamp = BattleClientState.stamp();
        if (stamp != null) PacketDistributor.sendToServer(new MoveChoicePacket(stamp, offer.token(), -1));
        Minecraft.getInstance().setScreen(null);
        BattleClientState.mode = BattleMode.MOVE;
        BattleAim.open();
    }

    @Override
    protected void back() {
        cancel(offer);
    }

    /**
     * The landing line is not a control, so it explains itself from the passive path: naming it here only
     * brings up its tooltip and never makes it clickable.
     */
    @Override
    protected String passiveHover(double guiX, double guiY) {
        if (targetShortened && layout != null && layout.rect(TARGET).contains(guiX, guiY)) return TARGET;
        return null;
    }

    @Override
    protected String[] tooltipFor(String id) {
        if (TARGET.equals(id)) return targetShortened && !targetFull.isEmpty() ? new String[]{targetFull} : null;
        // A route whose name had to be shortened still shows its whole name and its own price.
        if (WALK.equals(id) && walkShortened) {
            return com.tactical.neoforge.client.ui.layout.TooltipText.row(walkFull, offer.walkCost() + " AP");
        }
        if (DROP.equals(id) && dropShortened) {
            return com.tactical.neoforge.client.ui.layout.TooltipText.row(dropFull, offer.dropCost() + " AP");
        }
        if (BACK.equals(id) && backShortened) return new String[]{ClientText.get(ClientText.Keys.MOVE_BACK)};
        return null;
    }
}
