package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.network.AttackIntentPacket;
import com.tactical.neoforge.network.MoveIntentPacket;
import com.tactical.neoforge.network.PlaceBlockPacket;
import com.tactical.neoforge.network.UseItemPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/** AUI command aiming screen backed by the cursor's actual rendered world ray. */
public final class BattleAim {

    public static final String PAGE = "tactical/screens/battle_aim.html";

    private static BattleAimScreen screen;

    private BattleAim() {
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        // The battle menu is still the active screen when its buttons fire, so
        // replace it rather than bailing out on "a screen is open".
        if (minecraft.screen != null && !(minecraft.screen instanceof TacticalScreen)) {
            return;
        }
        screen = new BattleAimScreen();
        minecraft.setScreen(screen);
        screen.setPauseGame(false).setShowDefaultBackground(false);
        Document document = screen.getLinkedDocument();
        if (document == null) {
            return;
        }
        fill(document);
        Element back = document.getElementById("aim-back");
        if (back != null) {
            back.addEventListener("click", ignored -> back());
        }
    }

    public static void back() {
        Minecraft minecraft = Minecraft.getInstance();
        BattleClientState.mode = BattleMode.NONE;
        BattleClientState.selectedSlot = -1;
        minecraft.setScreen(null);
        BattleMenu.open();
    }

    public static void refreshLabels() {
        var mc=Minecraft.getInstance();
        if(mc.screen instanceof BattleAimScreen s && s.getLinkedDocument()!=null) fill(s.getLinkedDocument());
    }

    private static void fill(Document document) {
        String title;
        String hint;
        switch (BattleClientState.mode) {
            case MOVE -> {
                title = "选择移动目标";
                hint = "左键移动 · 右键返回";
            }
            case ATTACK -> {
                title = "选择攻击目标";
                hint = "左键攻击 · 右键返回";
            }
            case PLACE -> {
                title = "选择放置位置";
                hint = "左键放置 · 右键返回";
            }
            case USE_ITEM -> {
                title = "使用物品";
                hint = "左键确认使用 · 右键返回";
            }
            case SKILL -> {
                title = "释放技能";
                hint = "左键确认 · 右键返回";
            }
            default -> {
                title = "选择目标";
                hint = "左键执行 · 右键返回";
            }
        }
        hint += switch(BattleClientState.mode){
            case ATTACK -> " · 本次 "+BattleClientState.attackCost+" AP";
            case PLACE -> " · 本次 "+BattleClientState.placeCost+" AP";
            case USE_ITEM -> " · 本次 "+BattleClientState.itemCost+" AP";
            default -> "";
        };
        set(document, "aim-title", title);
        set(document, "aim-hint", hint);
    }

    private static void set(Document document, String id, String value) {
        Element element = document.getElementById(id);
        if (element != null) {
            element.setTextContent(value);
        }
    }

    /** Compatibility entry point for vanilla GUI coordinates. */
    public static HitResult pick(Minecraft minecraft, double mouseX, double mouseY) {
        return CursorWorldPicker.atGui(minecraft, mouseX, mouseY).hit();
    }

    public static void execute(Minecraft minecraft, HitResult hit) {
        BattleTool.refresh();
        BattleMode mode = BattleClientState.mode;
        if (hit == null && mode != BattleMode.USE_ITEM) return;
        if (mode == BattleMode.ATTACK) {
            if (hit instanceof net.minecraft.world.phys.EntityHitResult entityHit
                    && entityHit.getEntity() instanceof Mob mob) {
                BattleScreenInput.syncSelectedSlot();
                var stamp=BattleClientState.stamp();
                if(stamp==null)return;
                PacketDistributor.sendToServer(new AttackIntentPacket(stamp, mob.getUUID()));
                minecraft.setScreen(null);
            }
            return;
        }
        if (mode == BattleMode.SKILL) {
            var selected=SkillClientState.selected();
            var target=com.tactical.neoforge.client.SkillOverlay.target(hit);
            if(selected!=null&&target!=null&&selected.ready()&&SkillClientState.session()!=null
                    &&minecraft.level!=null&&minecraft.getConnection()!=null
                    &&minecraft.getConnection().hasChannel(com.tactical.neoforge.network.SkillCastPacket.TYPE)) {
                PacketDistributor.sendToServer(new com.tactical.neoforge.network.SkillCastPacket(SkillClientState.session(),BattleClientState.turnEpoch,minecraft.level.dimension().location(),selected.id(),target));
                SkillClientState.castConfirmed();minecraft.setScreen(null);
            }
            return;
        }
        if (mode == BattleMode.USE_ITEM) {
            if (BattleClientState.selectedSlot >= 0) {
                BattleScreenInput.syncSelectedSlot();
                var stamp=BattleClientState.stamp();
                if(stamp==null)return;
                PacketDistributor.sendToServer(new UseItemPacket(stamp, BattleClientState.selectedSlot));
                BattleClientState.mode = BattleMode.NONE;
                minecraft.setScreen(null);
            }
            return;
        }
        if (!(hit instanceof BlockHitResult blockHit)) {
            return;
        }
        if (mode == BattleMode.PLACE) {
            if (BattleClientState.selectedSlot >= 0) {
                BattleScreenInput.syncSelectedSlot();
                var stamp=BattleClientState.stamp();
                if(stamp==null)return;
                PacketDistributor.sendToServer(new PlaceBlockPacket(stamp, BattleClientState.selectedSlot,
                        blockHit.getBlockPos(), blockHit.getDirection(), blockHit.getLocation()));
                BattleClientState.mode = BattleMode.NONE;
                minecraft.setScreen(null);
            }
            return;
        }
        if (mode == BattleMode.MOVE) {
            GridPos cell = CursorWorldPicker.standCell(minecraft, blockHit);
            if (cell != null && BattleClientState.reachable.contains(cell)) {
                var stamp=BattleClientState.stamp();
                if(stamp==null)return;
                PacketDistributor.sendToServer(new MoveIntentPacket(stamp, cell.x(), cell.y(), cell.z()));
                minecraft.setScreen(null);
            }
        }
    }

    public static HitResult pickAtCursor() {
        return CursorWorldPicker.atCursor(Minecraft.getInstance()).hit();
    }

    /** AUI screen that forwards mouse position and clicks to the aim logic. */
    public static final class BattleAimScreen extends TacticalScreen {

        public BattleAimScreen() {
            super(PAGE, BattleAim::back);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (button == 1) {
                back();
                return true;
            }
            if (button == 0) {
                execute(minecraft, pickAtCursor());
                return true;
            }
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                back();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }
}
