package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.client.ui.facing.BattleFacingScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Local hover preview; only an explicit click commits facing and ends the turn.
 *
 * <p>The page is native ({@link BattleFacingScreen}); this class keeps the lifecycle and the one
 * place that writes the previewed rotation, so a preview can never leak past the page.</p>
 *
 * <p>The preview is bound to one actor when the page opens: the local avatar on a player turn, or the
 * companion this client commands. Nothing is written to a second actor, and the binding is dropped with its
 * saved rotation when the page closes - so an abandoned companion preview can never turn the player, and a
 * previous companion's facing can never be applied to the next one.</p>
 */
public final class BattleFacing {

    /** The actor this page is previewing, or null while it previews the local avatar. */
    private static LivingEntity bound;
    /** The bound actor's rotation before the preview started, so closing the page can restore it. */
    private static float[] boundOriginal;

    private BattleFacing() {
    }

    public static void open() {
        open(false);
    }

    public static void openDefend() {
        open(true);
    }

    private static void open(boolean defend) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || !BattleMenu.commandTurn()) return;
        // Without a resolvable commanded body the page must not open at all: a preview with no actor would
        // either do nothing or - worse - fall back to the player.
        if (!beginPreview()) return;
        CommandPageGuard.bind();
        mc.setScreen(new BattleFacingScreen(defend));
    }

    /**
     * Bind the preview to the actor this page will turn.
     *
     * <p>On a companion turn the target is the companion entity named by the authoritative projection; a
     * client that cannot find it keeps previewing its own body, which is what every non-commanding frame does
     * anyway.</p>
     */
    private static boolean beginPreview() {
        Minecraft mc = Minecraft.getInstance();
        bound = null;
        boundOriginal = null;
        if (!BattleClientState.isCommandingCompanion()) return true;
        if (mc.level == null) return false;
        Entity entity = mc.level.getEntity(BattleClientState.command.companionEntityId());
        if (!(entity instanceof LivingEntity living) || living.isRemoved()) {
            Tactical.LOGGER.info("[Tactical] command page refused: the commanded companion is not loaded");
            return false;
        }
        bound = living;
        boundOriginal = new float[]{living.getYRot(), living.yHeadRot, living.yBodyRot};
        return true;
    }

    /**
     * Finish the page after a committed order: the binding is dropped without putting the old rotation back,
     * because the server has just been told which facing to keep.
     */
    public static void commit() {
        bound = null;
        boundOriginal = null;
        CommandPageGuard.clear();
    }

    /** Drop the preview binding and put the bound actor's own rotation back. */
    public static void endPreview() {
        if (bound != null && boundOriginal != null && !bound.isRemoved()) {
            bound.setYRot(boundOriginal[0]);
            bound.yRotO = boundOriginal[0];
            bound.setYHeadRot(boundOriginal[1]);
            bound.yHeadRotO = boundOriginal[1];
            bound.setYBodyRot(boundOriginal[2]);
            bound.yBodyRotO = boundOriginal[2];
        }
        bound = null;
        boundOriginal = null;
    }

    /**
     * The actor this page turns right now.
     *
     * <p>While a companion is commanded the answer is only ever that companion: an unresolved binding returns
     * null instead of the player, so no page can ever turn the owner's body on the companion's behalf.</p>
     */
    public static LivingEntity previewActor() {
        if (bound != null) return bound;
        return BattleClientState.isCommandingCompanion() ? null : Minecraft.getInstance().player;
    }

    public static boolean isOpen() {
        return Minecraft.getInstance().screen instanceof BattleFacingScreen;
    }

    /** Keeps the preview applied while the page is up, so a movement tick cannot undo it. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BattleFacingScreen) {
            // A page whose actor, session or turn changed is closed rather than turned into the next body's
            // order. Closing runs the page's own restore on the actor that is still bound.
            if (!BattleMenu.commandTurn() || BattleClientState.commandMoving() || !CommandPageGuard.matches()) {
                mc.setScreen(null);
                endPreview();
                CommandPageGuard.clear();
                return;
            }
        }
        applyPreview(mc.screen);
    }

    /** Writes the previewed actor's rotation: preview while the page is up, nothing afterwards. */
    public static void orient(float yaw, float head, float body) {
        LivingEntity actor = previewActor();
        if (actor == null) return;
        actor.setYRot(yaw);
        actor.yRotO = yaw;
        actor.setYHeadRot(head);
        actor.yHeadRotO = head;
        actor.setYBodyRot(body);
        actor.yBodyRotO = body;
    }

    static void applyPreview(net.minecraft.client.gui.screens.Screen screen) {
        if (screen instanceof BattleFacingScreen facing) facing.applyPreview();
    }

    public static void onRenderFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        applyPreview(Minecraft.getInstance().screen);
    }

    public static void onRenderPlayer(net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre event) {
        // Only the local avatar's own preview belongs on this hook; a bound companion is turned on its entity.
        if (bound == null && event.getEntity() == Minecraft.getInstance().player) {
            applyPreview(Minecraft.getInstance().screen);
        }
    }

    /**
     * Return to the command panel without committing a facing.
     *
     * <p>The screen is cleared first: its own teardown restores the previewed pose on the actor that is still
     * bound, and only then is the binding dropped. Doing it the other way round let the teardown write the
     * old facing onto whoever the fallback would answer - the owner's body.</p>
     */
    public static void back() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);
        endPreview();
        CommandPageGuard.clear();
        BattleMenu.open();
    }
}
