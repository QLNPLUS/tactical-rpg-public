package com.tactical.neoforge.client.ui.aim;

import com.tactical.neoforge.client.UiPalette;
import com.tactical.neoforge.client.BattleAim;
import com.tactical.neoforge.client.BattleClientState;
import com.tactical.neoforge.client.BattleMode;
import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.panel.NativePanelScreen;
import com.tactical.neoforge.client.ui.panel.PanelLayout;
import com.tactical.neoforge.client.ui.platform.Draw;
import com.tactical.neoforge.client.ui.platform.Portraits;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Slate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * Target aiming: one panel replacing the command panel, with the mode, the hint, the budget and the
 * target slot.
 *
 * <p>It is the only page that hands a click outside its own rectangle to the battlefield, because
 * choosing a target is exactly a click on the world. Inside the panel nothing selects anything; the
 * footer only goes back. Names, cells and skill previews are read live from the same sources the
 * execution path uses, so the slot can never announce a target the click would not send.</p>
 */
public final class BattleAimScreen extends NativePanelScreen {

    public static final String SCREEN_ID = "battle_aim";
    public static final String ICON = "mode-icon";
    public static final String HINT = "hint";
    public static final String AP = "ap";
    public static final String AP_BAR = "ap-bar";
    public static final String MP = "mp";
    public static final String MP_BAR = "mp-bar";
    public static final String SLOT = "target-slot";
    /** The commanded actor's identity row, present only on a companion turn. */
    public static final String ACTOR = "actor";
    public static final String BACK = "back";

    private static final double TITLE_HEIGHT = 16;
    private static final double SLOT_HEIGHT = 20;

    private String hintFull = "";
    /** Whether this frame's hint line had to be shortened, so the whole sentence can hang on a tooltip. */
    private boolean hintTruncated;
    private String slotFull = "";
    private String titleFull = "";

    @Override
    public String screenId() {
        return SCREEN_ID;
    }

    @Override
    protected PanelLayout build(int guiWidth, int guiHeight, double scale, boolean narrow) {
        PanelLayout layout = PanelLayout.begin(guiWidth, guiHeight, scale, narrow);
        layout.block(PanelLayout.TITLE, TITLE_HEIGHT);
        layout.rule();
        layout.block(HINT, Motion.LINE);
        if (BattleClientState.isCommandingCompanion()) {
            // Companion identity row: one line, added only on a companion turn so the player page keeps its
            // exact height.
            layout.gap(Motion.STEP);
            layout.block(ACTOR, Motion.LINE);
        }
        layout.gap(Motion.ROW_GAP);
        layout.block(AP, Motion.LINE);
        layout.gap(Motion.STEP + 1);
        layout.block(AP_BAR, Motion.BAR_HEIGHT);
        if (BattleClientState.mode == BattleMode.SKILL) {
            layout.gap(Motion.ROW_GAP);
            layout.block(MP, Motion.LINE);
            layout.gap(Motion.STEP + 1);
            layout.block(MP_BAR, Motion.BAR_HEIGHT);
        }
        layout.gap(Motion.ROW_GAP);
        layout.block(SLOT, SLOT_HEIGHT);
        int backWidth = layout.px(buttonDesignWidth(ClientText.get(ClientText.Keys.BACK_TO_COMMANDS)));
        layout.footer(List.of(BACK), List.of(backWidth), Motion.BUTTON_HEIGHT);
        return layout.finish();
    }

    @Override
    protected void drawBody(GuiGraphics graphics, Font font, double scale, double alpha, long now, double delta) {
        Minecraft minecraft = Minecraft.getInstance();
        HitResult hit = BattleAim.pickAtCursor();
        BattleAim.Slot slot = BattleAim.slot(minecraft, hit);

        Rect title = layout.rect(PanelLayout.TITLE);
        Rect icon = Rect.of(title.x(), title.y(), Scale.effectivePx(Motion.ICON, scale),
                Scale.effectivePx(Motion.ICON, scale));
        Draw.chip(graphics, icon, alpha(Slate.SUNK, alpha));
        item(graphics, modeIcon(minecraft), icon);
        titleFull = BattleAim.title();
        int textX = title.x() + Scale.effectivePx(20, scale);
        Draw.scaledText(graphics, font, ellipsis(font, titleFull, title.right() - textX), textX,
                title.y() + Math.max(0, (title.h() - Scale.effectivePx(Motion.LINE, scale)) / 2), scale,
                alpha(Slate.TEXT, alpha));
        drawRule(graphics);

        drawHint(graphics, font, scale, alpha);
        drawActorRow(graphics, font, scale, alpha);
        drawBudget(graphics, font, scale, alpha);
        drawSlot(graphics, font, scale, alpha, slot);
        button(graphics, font, scale, BACK, ClientText.get(ClientText.Keys.BACK_TO_COMMANDS), true, alpha, now,
                delta);
    }

    /** The hint line, with the attempt's own cost in the AP colour as the specification asks. */
    private void drawHint(GuiGraphics graphics, Font font, double scale, double alpha) {
        Rect hint = layout.rect(HINT);
        int y = baseline(hint, scale);
        String base = BattleAim.hint();
        int cost = BattleAim.cost();
        String costLead = ClientText.get(ClientText.Keys.AIM_COST_LEAD);
        String costValue = cost > 0 ? cost + " AP" : "";
        String costText = costLead + costValue;
        hintFull = cost > 0 ? base + costText : base;
        int budget = hint.w();
        int x = hint.x();
        if (cost == 0) {
            String shown = ellipsis(font, base, budget);
            hintTruncated = !shown.equals(base);
            Draw.scaledText(graphics, font, shown, x, y, scale, alpha(Slate.TEXT2, alpha));
            return;
        }
        // The attempt's own price is the number the player is deciding with, so it is drawn first and in
        // full; the hint's wording fills whatever is left, with the ellipsis. The whole sentence - wording
        // and price - stays readable in the tooltip.
        int valueWidth = textWidth(font, costValue);
        int leadBudget = Math.max(0, budget - valueWidth);
        String shownLead = ellipsis(font, costLead, leadBudget);
        int leadWidth = textWidth(font, shownLead);
        int baseBudget = Math.max(0, budget - valueWidth - leadWidth);
        String shownBase = ellipsis(font, base, baseBudget);
        hintTruncated = !(shownBase.equals(base) && shownLead.equals(costLead));
        Draw.scaledText(graphics, font, shownBase, x, y, scale, alpha(Slate.TEXT2, alpha));
        x += textWidth(font, shownBase);
        if (x >= hint.right()) return;
        Draw.scaledText(graphics, font, shownLead, x, y, scale, alpha(Slate.TEXT2, alpha));
        x += leadWidth;
        Draw.scaledText(graphics, font, costValue, x, y, scale, alpha(Slate.AP, alpha));
    }

    /**
     * The commanded actor's row: the existing value-row shape, label on the left and the name on the right
     * followed by the identity chip.
     *
     * <p>It exists only while a companion is commanding, so a player page is unchanged. The name gives way
     * with the ordinary ellipsis; the chip never shortens and never renames.</p>
     */
    private void drawActorRow(GuiGraphics graphics, Font font, double scale, double alpha) {
        Rect row = layout.rect(ACTOR);
        if (row.w() <= 0) return;
        int y = baseline(row, scale);
        Draw.scaledText(graphics, font, ClientText.get(ClientText.Keys.COMMAND_ACTOR_LABEL), row.x(), y, scale,
                alpha(Slate.TEXT2, alpha));
        String chip = ClientText.get(ClientText.Keys.COMMAND_CHIP_COMPANION);
        int chipWidth = textWidth(font, chip) + Scale.effectivePx(2, scale) * 2;
        int chipHeight = Scale.effectivePx(9, scale);
        int chipX = row.right() - chipWidth;
        int nameRight = chipX - Scale.effectivePx(4, scale);
        String name = BattleClientState.command.name() == null ? "" : BattleClientState.command.name().getString();
        int nameX = row.x() + textWidth(font, ClientText.get(ClientText.Keys.COMMAND_ACTOR_LABEL)) + Scale.effectivePx(4, scale);
        Draw.scaledText(graphics, font, ellipsis(font, name, Math.max(1, nameRight - nameX)), nameX,
                y, scale, alpha(Slate.TEXT, alpha));
        Rect chipBox = Rect.of(chipX, row.y() + (row.h() - chipHeight) / 2, chipWidth, chipHeight);
        Draw.chip(graphics, chipBox, alpha(Slate.RAISE, alpha));
        Draw.scaledText(graphics, font, chip, chipX + Scale.effectivePx(2, scale),
                chipBox.y() + (chipHeight - Math.max(1, Scale.effectivePx(Motion.LINE, scale))) / 2, scale,
                alpha(Slate.TEXT2, alpha));
    }

    /**
     * The budget rows: AP with the attempt's own cost as the pending segment, plus the MP row and bar
     * the skill mode adds. A cost larger than the current value only turns the number red - the
     * server still decides whether the action is allowed.
     */
    private void drawBudget(GuiGraphics graphics, Font font, double scale, double alpha) {
        int ap = BattleClientState.commandAp();
        int maxAp = Math.max(1, BattleClientState.commandMaxAp());
        int cost = BattleAim.cost();
        boolean shortOnAp = cost > ap;
        Rect apRow = layout.rect(AP);
        valueRowParts(graphics, font, scale, apRow, "AP", Integer.toString(ap),
                shortOnAp ? Slate.HP : Slate.TEXT, " / " + maxAp, alpha);
        Rect apBar = layout.rect(AP_BAR);
        double fraction = Math.max(0.0D, Math.min(1.0D, ap / (double) maxAp));
        double pendingFrom = Math.max(0.0D, Math.min(fraction, (ap - cost) / (double) maxAp));
        Draw.barPending(graphics, apBar, alpha(Slate.AP, alpha), alpha(Slate.AP_PENDING, alpha), fraction,
                cost > 0 ? pendingFrom : fraction);

        Rect mpRow = layout.rect(MP);
        if (mpRow.w() <= 0) return;
        double mp = BattleClientState.mp;
        double maxMp = Math.max(1.0D, BattleClientState.maxMp);
        int mpCost = BattleAim.mpCost();
        String mpValue = com.tactical.neoforge.client.SkillClientState.number(mp);
        valueRowParts(graphics, font, scale, mpRow, "MP", mpValue,
                mpCost > mp ? Slate.HP : Slate.MP, " / " + com.tactical.neoforge.client.SkillClientState.number(maxMp),
                alpha);
        Rect mpBar = layout.rect(MP_BAR);
        double mpFraction = Math.max(0.0D, Math.min(1.0D, mp / maxMp));
        double mpFrom = Math.max(0.0D, Math.min(mpFraction, (mp - mpCost) / maxMp));
        Draw.barPending(graphics, mpBar, alpha(Slate.MP, alpha), Motion.withAlpha(Slate.MP, 0.45D * alpha), mpFraction,
                mpCost > 0 ? mpFrom : mpFraction);
    }

    /** "AP 42 / 100" with the label and the ceiling secondary and the value in its own colour. */
    private void valueRowParts(GuiGraphics graphics, Font font, double scale, Rect row, String label, String value,
                               int valueColor, String ceiling, double alpha) {
        if (row.w() <= 0) return;
        int y = baseline(row, scale);
        Draw.scaledText(graphics, font, label + " ", row.x(), y, scale, alpha(Slate.TEXT2, alpha));
        int ceilingWidth = textWidth(font, ceiling);
        Draw.scaledText(graphics, font, ceiling, row.right() - ceilingWidth, y, scale, alpha(Slate.TEXT2, alpha));
        int valueWidth = textWidth(font, value);
        Draw.scaledText(graphics, font, value, row.right() - ceilingWidth - valueWidth, y, scale,
                alpha(valueColor, alpha));
    }

    /**
     * The target slot. A valid target raises the well, lights its icon box and draws the entity's own
     * model for an entity target; anything the client cannot accept keeps the sunk well and states why
     * in tertiary text.
     */
    private void drawSlot(GuiGraphics graphics, Font font, double scale, double alpha, BattleAim.Slot slot) {
        Rect row = layout.rect(SLOT);
        boolean valid = slot.valid() && !slot.empty();
        Rect box = valid ? row.move(0, -1) : row;
        Draw.fill(graphics, box, alpha(valid ? Slate.HOVER : Slate.SUNK, alpha));
        Rect well = Rect.of(box.x() + Scale.effectivePx(Motion.STEP, scale),
                box.y() + Scale.effectivePx(Motion.STEP, scale), Scale.effectivePx(Motion.ICON, scale),
                Scale.effectivePx(Motion.ICON, scale));
        Draw.chip(graphics, well, alpha(valid ? UiPalette.accentDark() : Slate.RAISE, alpha));
        if (valid && BattleClientState.mode == BattleMode.ATTACK) {
            HitResult hit = BattleAim.pickAtCursor();
            if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof LivingEntity living) {
                Portraits.entity(graphics, living, well.x(), well.y(), well.right(), well.bottom(),
                        well.x() + well.w() / 2.0F, well.y() + well.h() / 2.0F);
            }
        }
        int textX = box.x() + Scale.effectivePx(22, scale);
        int y = baseline(box, scale);
        slotFull = slot.main() + slot.suffix();
        String budgetText = ellipsis(font, slot.main(), Math.max(1, box.right() - Scale.effectivePx(4, scale) - textX));
        Draw.scaledText(graphics, font, budgetText, textX, y, scale,
                alpha(valid ? Slate.TEXT : Slate.TEXT3, alpha));
        if (!slot.suffix().isEmpty() && budgetText.equals(slot.main()) && !slot.main().isEmpty()) {
            int suffixWidth = Math.max(1, box.right() - Scale.effectivePx(4, scale) - textX - textWidth(font, budgetText));
            String suffix = ellipsis(font, slot.suffix(), suffixWidth);
            Draw.scaledText(graphics, font, suffix, textX + textWidth(font, budgetText), y, scale,
                    alpha(slot.suffixColor() == 0 ? Slate.TEXT3 : slot.suffixColor(), alpha));
        }
    }

    /** The mode icon reuses the command panel's mapping, so both pages show the same item. */
    /**
     * The attack page's own symbol.
     *
     * <p>While a companion is being commanded the icon has to describe that companion, not its owner:
     * the weapon it really holds when it holds one, otherwise the sword the approved specification uses
     * for a natural attack, and the bow or crossbow when the projection reports a ranged natural weapon.
     * The owner's own page keeps showing exactly the main hand it always did.</p>
     */
    private static ItemStack attackIcon(Minecraft minecraft) {
        if (!BattleClientState.isCommandingCompanion()) {
            return minecraft != null && minecraft.player != null
                    ? minecraft.player.getMainHandItem() : ItemStack.EMPTY;
        }
        LivingEntity actor = companionActor(minecraft);
        ItemStack held = actor == null ? ItemStack.EMPTY : actor.getMainHandItem();
        if (!held.isEmpty()) return held;
        return switch (BattleClientState.commandWeaponKind()) {
            case 1 -> new ItemStack(Items.BOW);
            case 2 -> new ItemStack(Items.CROSSBOW);
            default -> new ItemStack(Items.IRON_SWORD);
        };
    }

    /** The commanded companion entity itself, when this client has it loaded. */
    private static LivingEntity companionActor(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) return null;
        net.minecraft.world.entity.Entity entity = minecraft.level.getEntity(BattleClientState.commandEntityId());
        return entity instanceof LivingEntity living ? living : null;
    }

    private static ItemStack modeIcon(Minecraft minecraft) {
        return switch (BattleClientState.mode) {
            case MOVE -> new ItemStack(Items.IRON_BOOTS);
            case ATTACK -> attackIcon(minecraft);
            case SKILL -> new ItemStack(Items.ENCHANTED_BOOK);
            case PLACE, USE_ITEM -> new ItemStack(Items.POTION);
            // The block page shows what the hand really holds, so placing and breaking are told apart by
            // the item itself rather than by a second symbol.
            case BREAK -> minecraft != null && minecraft.player != null
                    ? minecraft.player.getMainHandItem() : new ItemStack(Items.IRON_PICKAXE);
            default -> ItemStack.EMPTY;
        };
    }

    @Override
    protected String passiveHover(double guiX, double guiY) {
        if (hintTruncated && layout.rect(HINT).contains(guiX, guiY)) return HINT;
        if (!slotFull.isEmpty() && layout.rect(SLOT).contains(guiX, guiY)) return SLOT;
        return null;
    }

    protected String[] tooltipFor(String id) {
        if (ICON.equals(id)) return null;
        // The hint is checked by what was really shortened, not by re-measuring: a cost-only line is
        // narrower than the whole sentence and would otherwise claim it fits.
        if (HINT.equals(id) && hintTruncated && !hintFull.isEmpty() && layout.rect(HINT).w() > 0) {
            return new String[]{hintFull};
        }
        if (SLOT.equals(id) && !slotFull.isEmpty() && textWidth(font, slotFull) > layout.rect(SLOT).w()
                - Scale.effectivePx(26, scale())) {
            return new String[]{slotFull};
        }
        return null;
    }

    @Override
    protected void activate(String id) {
        if (BACK.equals(id)) back();
    }

    /**
     * Read-only development report of what the page is doing: the mode and the target the cursor's own
     * world ray currently resolves to. It exists so an acceptance driver can show that a HUD row click
     * did not come from that ray, instead of inferring it from pixels. Never an action.
     */
    @Override
    protected String probeExtra() {
        HitResult hit = BattleAim.pickAtCursor();
        BattleAim.Slot slot = BattleAim.slot(Minecraft.getInstance(), hit);
        String kind = hit == null ? "miss" : hit.getType().name().toLowerCase(java.util.Locale.ROOT);
        return String.format(java.util.Locale.ROOT,
                "{\"aimMode\":\"%s\",\"worldHit\":\"%s\",\"worldTarget\":\"%s\",\"cost\":%d,\"mpCost\":%d}",
                BattleClientState.mode, kind, escapeProbe(slot.main()), BattleAim.cost(), BattleAim.mpCost());
    }

    /** Minimal JSON escaping for the probe string; the page's own text can contain quotes and gaps. */
    private static String escapeProbe(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    @Override
    protected void back() {
        BattleAim.back();
    }

    /** A click outside the panel is a battlefield click: that is how a target is chosen. */
    @Override
    protected boolean worldClick(double pointerX, double pointerY) {
        BattleAim.execute(Minecraft.getInstance(), BattleAim.pickAtCursor());
        return true;
    }
}
