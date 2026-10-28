package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.tactical.neoforge.network.EndTurnFacingPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/** Local hover preview; only an explicit click commits facing and ends the turn. */
public final class BattleFacing {
    public static final String PAGE = "tactical/screens/battle_facing.html";
    private BattleFacing() {}

    public static void open() { open(false); }
    public static void openDefend() { open(true); }
    private static void open(boolean defend) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || !BattleClientState.isPlayerTurn()) return;
        FacingScreen screen = new FacingScreen(defend);
        screen.setPauseGame(false).setShowDefaultBackground(false);
        mc.setScreen(screen);
    }

    public static boolean isOpen() { return Minecraft.getInstance().screen instanceof FacingScreen; }

    public static void tick() {
        if (Minecraft.getInstance().screen instanceof FacingScreen screen) {
            screen.bind();
            screen.applyPreview();
        }
    }

    private static final class FacingScreen extends TacticalScreen {
        private final float yaw;
        private final float head;
        private final float body;
        private long generation = -1;
        private boolean confirmed;
        private final boolean defend;
        private Float previewYaw;

        FacingScreen(boolean defend) {
            super(PAGE, BattleFacing::back);
            this.defend=defend;
            var player = Minecraft.getInstance().player;
            yaw = player.getYRot(); head = player.yHeadRot; body = player.yBodyRot;
        }

        @Override protected void init() { super.init(); bind(); }

        void bind() {
            Document d = getLinkedDocument();
            if (d == null || generation == d.getRefreshGeneration()) return;
            generation = d.getRefreshGeneration();
            var subtitle=d.querySelector(".subtitle");
            if(subtitle!=null)subtitle.setTextContent(defend?"确认朝向 · 消耗 25 AP 并结束回合":"确认后结束本回合");
            wire(d, "face-north", 180);
            wire(d, "face-south", 0);
            wire(d, "face-west", 90);
            wire(d, "face-east", 270);
            var back = d.getElementById("facing-back");
            if (back != null) back.addEventListener("click", event -> Minecraft.getInstance().tell(BattleFacing::back));
        }

        void wire(Document d, String id, int direction) {
            var button = d.getElementById(id);
            if (button == null) return;
            button.addEventListener("mouseenter", event -> preview(direction));
            button.addEventListener("focus", event -> preview(direction));
            button.addEventListener("mouseleave", event -> restore());
            button.addEventListener("blur", event -> restore());
            button.addEventListener("click", event -> {
                event.preventDefault();
                Minecraft.getInstance().tell(() -> {
                    if (!BattleClientState.isPlayerTurn() || BattleClientState.moving) return;
                    confirmed = true;
                    preview(direction);
                    var stamp = BattleClientState.stamp();
                    if (stamp == null) return;
                    if(defend) PacketDistributor.sendToServer(new com.tactical.neoforge.network.DefendPacket(stamp, direction));
                    else PacketDistributor.sendToServer(new EndTurnFacingPacket(stamp, direction));
                    BattleClientState.mode = BattleMode.NONE;
                    Minecraft.getInstance().setScreen(null);
                });
            });
        }

        void preview(float direction) { previewYaw = direction; applyPreview(); }
        void applyPreview() {
            if (previewYaw != null) orient(previewYaw, previewYaw, previewYaw);
            else if (!confirmed) orient(yaw, head, body);
        }
        void restore() {
            if (!confirmed) {
                previewYaw = null;
                orient(yaw, head, body);
            }
        }

        private void orient(float nextYaw, float nextHead, float nextBody) {
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            player.setYRot(nextYaw); player.yRotO = nextYaw;
            player.setYHeadRot(nextHead); player.yHeadRotO = nextHead;
            player.setYBodyRot(nextBody); player.yBodyRotO = nextBody;
        }

        @Override public void removed() { restore(); super.removed(); }
    }

    public static void onRenderFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        if (Minecraft.getInstance().screen instanceof FacingScreen screen) screen.applyPreview();
    }

    public static void onRenderPlayer(net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre event) {
        if (event.getEntity() == Minecraft.getInstance().player
                && Minecraft.getInstance().screen instanceof FacingScreen screen) screen.applyPreview();
    }

    private static void back() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);
        BattleMenu.open();
    }
}
