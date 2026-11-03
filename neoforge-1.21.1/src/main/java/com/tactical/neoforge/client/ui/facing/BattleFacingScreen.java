package com.tactical.neoforge.client.ui.facing;

import com.tactical.neoforge.client.UiPalette;
import com.tactical.neoforge.client.BattleClientState;
import com.tactical.neoforge.client.BattleFacing;
import com.tactical.neoforge.client.BattleMode;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.neoforge.client.ui.panel.NativePanelScreen;
import com.tactical.neoforge.client.ui.panel.PanelLayout;
import com.tactical.neoforge.client.ui.platform.Draw;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.theme.Slate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Facing confirmation: four directions, a live preview on the world, and one commit.
 *
 * <p>Hovering a direction turns the avatar immediately so the player can see what they are choosing;
 * leaving it puts the original pose back; only a click sends the packet. Dismissing the page - its own
 * button, a right click, ESC or the screen being removed - restores the pose exactly, because a
 * preview must never survive the page that caused it.</p>
 */
public final class BattleFacingScreen extends NativePanelScreen {

    public static final String SCREEN_ID = "battle_facing";
    public static final String SUBTITLE = "subtitle";
    /** The commanded actor's identity row, present only on a companion turn. */
    public static final String ACTOR = "actor";
    public static final String NORTH = "face-north";
    public static final String SOUTH = "face-south";
    public static final String WEST = "face-west";
    public static final String EAST = "face-east";
    public static final String BACK = "back";

    private static final String[] DIRECTIONS = {NORTH, SOUTH, WEST, EAST};
    private static final int[] YAWS = {180, 0, 90, 270};
    /** The four directions, worded per language at the display point. */
    private static final String[] LABEL_KEYS = {
            ClientText.Keys.FACING_NORTH, ClientText.Keys.FACING_SOUTH,
            ClientText.Keys.FACING_WEST, ClientText.Keys.FACING_EAST};

    private final boolean defend;
    private final float yaw;
    private final float head;
    private final float body;
    private Float previewYaw;
    private boolean confirmed;

    public BattleFacingScreen(boolean defend) {
        this.defend = defend;
        // The initial pose belongs to the actor this page will turn: the avatar, or the commanded companion.
        net.minecraft.world.entity.LivingEntity actor = BattleFacing.previewActor();
        this.yaw = actor == null ? 0.0F : actor.getYRot();
        this.head = actor == null ? 0.0F : actor.yHeadRot;
        this.body = actor == null ? 0.0F : actor.yBodyRot;
    }

    @Override
    public String screenId() {
        return SCREEN_ID;
    }

    public boolean defend() {
        return defend;
    }

    @Override
    protected PanelLayout build(int guiWidth, int guiHeight, double scale, boolean narrow) {
        PanelLayout layout = PanelLayout.begin(guiWidth, guiHeight, scale, narrow);
        layout.title();
        layout.block(SUBTITLE, Motion.LINE);
        if (BattleClientState.isCommandingCompanion()) {
            // One value row between the subtitle and the rule, only while a companion is commanding.
            layout.gap(Motion.STEP);
            layout.block(ACTOR, Motion.LINE);
        }
        layout.rule();
        int gap = layout.px(Motion.ROW_GAP);
        int cell = Math.max(1, (layout.innerWidth() - gap) / 2);
        int height = layout.px(Motion.BUTTON_HEIGHT);
        int top = layout.cursor();
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 2; column++) {
                int index = row * 2 + column;
                Rect rect = Rect.of(layout.innerX() + column * (cell + gap), top + row * (height + gap), cell, height);
                layout.interactive(DIRECTIONS[index], rect);
            }
        }
        layout.moveTo(top + height * 2 + gap);
        int backWidth = layout.px(buttonDesignWidth(ClientText.get(ClientText.Keys.BACK_TO_COMMANDS)));
        layout.footer(List.of(BACK), List.of(backWidth), Motion.BUTTON_HEIGHT);
        return layout.finish();
    }

    @Override
    protected void drawBody(GuiGraphics graphics, Font font, double scale, double alpha, long now, double delta) {
        drawTitle(graphics, font, scale, ClientText.get(ClientText.Keys.FACING_TITLE), alpha);
        Rect subtitle = layout.rect(SUBTITLE);
        int y = baseline(subtitle, scale);
        int x = subtitle.x();
        String lead = ClientText.get(ClientText.Keys.FACING_CONFIRM_LEAD);
        String cost = "25 AP";
        String tail = ClientText.get(ClientText.Keys.FACING_CONFIRM_TAIL);
        if (defend) {
            Draw.scaledText(graphics, font, ellipsis(font, lead, subtitle.w()), x, y, scale, alpha(Slate.TEXT2, alpha));
            x += textWidth(font, lead);
            Draw.scaledText(graphics, font, cost, x, y, scale, alpha(Slate.AP, alpha));
            x += textWidth(font, cost);
            Draw.scaledText(graphics, font, ellipsis(font, tail, Math.max(1, subtitle.right() - x)), x, y, scale,
                    alpha(Slate.TEXT2, alpha));
        } else {
            Draw.scaledText(graphics, font,
                    ellipsis(font, ClientText.get(ClientText.Keys.FACING_PLAIN), subtitle.w()), x, y, scale,
                    alpha(Slate.TEXT2, alpha));
        }
        drawActorRow(graphics, font, scale, alpha);
        drawRule(graphics);

        // Hovering a direction previews it at once; leaving it restores the original pose.
        Float hoverYaw = null;
        for (int index = 0; index < DIRECTIONS.length; index++) {
            if (DIRECTIONS[index].equals(hoveredId)) hoverYaw = (float) YAWS[index];
        }
        if (!java.util.Objects.equals(hoverYaw, previewYaw)) setPreview(hoverYaw);

        for (int index = 0; index < DIRECTIONS.length; index++) {
            String id = DIRECTIONS[index];
            Rect rect = layout.rect(id);
            boolean selected = previewYaw != null && Math.abs(previewYaw - YAWS[index]) < 0.001F;
            boolean hovered = id.equals(hoveredId);
            double progress = tween.progress(id, hovered, now, Motion.HOVER_MS, delta);
            int base = selected ? UiPalette.accentDark() : Draw.blend(Slate.RAISE, Slate.HOVER, progress);
            Draw.chip(graphics, rect, alpha(base, alpha));
            String label = ClientText.get(LABEL_KEYS[index]);
            int labelWidth = textWidth(font, label);
            Draw.scaledText(graphics, font, label, rect.x() + (rect.w() - labelWidth) / 2, baseline(rect, scale), scale,
                    alpha(selected ? Slate.TEXT : Slate.TEXT, alpha));
        }
        button(graphics, font, scale, BACK, ClientText.get(ClientText.Keys.BACK_TO_COMMANDS), true, alpha, now,
                delta);
    }

    @Override
    protected void activate(String id) {
        for (int index = 0; index < DIRECTIONS.length; index++) {
            if (!DIRECTIONS[index].equals(id)) continue;
            confirm(YAWS[index]);
            return;
        }
        if (BACK.equals(id)) back();
    }

    /** Sends the one packet this page exists for; the server decides what happens next. */
    private void confirm(int direction) {
        // The facing page serves both the player body and the commanded companion; the server decides whose
        // turn the order belongs to, so the page only has to know that *something* is being commanded.
        if (!com.tactical.neoforge.client.BattleMenu.commandTurn() || BattleClientState.commandMoving()) return;
        // A page opened for another actor or another turn may not confirm under the current stamp.
        if (!com.tactical.neoforge.client.CommandPageGuard.matches()) return;
        previewYaw = (float) direction;
        BattleFacing.orient(previewYaw, previewYaw, previewYaw);
        var stamp = BattleClientState.stamp();
        if (stamp == null) return;
        confirmed = true;
        // The committed facing belongs to this actor; the binding is dropped without restoring it.
        BattleFacing.commit();
        if (defend) {
            PacketDistributor.sendToServer(new com.tactical.neoforge.network.DefendPacket(stamp, direction));
        } else {
            PacketDistributor.sendToServer(new com.tactical.neoforge.network.EndTurnFacingPacket(stamp, direction));
        }
        BattleClientState.mode = BattleMode.NONE;
        Minecraft.getInstance().setScreen(null);
    }

    /** The commanded actor's identity row: same value-row shape as the aiming page. */
    private void drawActorRow(GuiGraphics graphics, Font font, double scale, double alpha) {
        if (!BattleClientState.isCommandingCompanion()) return;
        Rect row = layout.rect(ACTOR);
        if (row.w() <= 0) return;
        int y = baseline(row, scale);
        String label = ClientText.get(ClientText.Keys.COMMAND_ACTOR_LABEL);
        Draw.scaledText(graphics, font, label, row.x(), y, scale, alpha(Slate.TEXT2, alpha));
        String chip = ClientText.get(ClientText.Keys.COMMAND_CHIP_COMPANION);
        int chipWidth = textWidth(font, chip) + Scale.effectivePx(2, scale) * 2;
        int chipHeight = Scale.effectivePx(9, scale);
        int chipX = row.right() - chipWidth;
        int nameX = row.x() + textWidth(font, label) + Scale.effectivePx(4, scale);
        String name = BattleClientState.command.name() == null ? "" : BattleClientState.command.name().getString();
        Draw.scaledText(graphics, font,
                ellipsis(font, name, Math.max(1, chipX - Scale.effectivePx(4, scale) - nameX)), nameX, y, scale,
                alpha(Slate.TEXT, alpha));
        Rect chipBox = Rect.of(chipX, row.y() + (row.h() - chipHeight) / 2, chipWidth, chipHeight);
        Draw.chip(graphics, chipBox, alpha(Slate.RAISE, alpha));
        Draw.scaledText(graphics, font, chip, chipX + Scale.effectivePx(2, scale),
                chipBox.y() + (chipHeight - Math.max(1, Scale.effectivePx(Motion.LINE, scale))) / 2, scale,
                alpha(Slate.TEXT2, alpha));
    }

    /** Keeps the avatar on the previewed heading, or on its original pose when nothing is previewed. */
    public void applyPreview() {
        if (previewYaw != null) {
            BattleFacing.orient(previewYaw, previewYaw, previewYaw);
        } else if (!confirmed) {
            BattleFacing.orient(yaw, head, body);
        }
    }

    void setPreview(Float yaw) {
        this.previewYaw = yaw;
        applyPreview();
    }

    /** Undo the preview: called by every dismissal path. */
    public void restore() {
        if (confirmed) return;
        previewYaw = null;
        BattleFacing.orient(yaw, head, body);
    }

    @Override
    protected void back() {
        BattleFacing.back();
    }

    @Override
    public void removed() {
        // Cancel restores the previewed pose on the still-bound actor; a confirmed page restores nothing,
        // because commit() already dropped the binding.
        restore();
        BattleFacing.endPreview();
        com.tactical.neoforge.client.CommandPageGuard.clear();
        super.removed();
    }

    @Override
    protected String[] tooltipFor(String id) {
        if (!SUBTITLE.equals(id)) return null;
        String cost = "25 AP";
        return new String[]{defend
                ? ClientText.get(ClientText.Keys.FACING_CONFIRM_LEAD) + cost
                        + ClientText.get(ClientText.Keys.FACING_CONFIRM_TAIL)
                : ClientText.get(ClientText.Keys.FACING_PLAIN)};
    }
}
