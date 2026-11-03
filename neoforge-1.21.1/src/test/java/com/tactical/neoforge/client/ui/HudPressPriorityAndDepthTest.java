package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.BattleInputPolicy;
import com.tactical.neoforge.client.ui.screen.TacticalSurface;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two defects that are about layering rather than drawing: which layer a press belongs to when the
 * interface and the vanilla quick bar overlap, and how deep the HUD's own floating text is drawn.
 *
 * <p>The routing half is the pure rule the input listener calls; the depth half is the named contract
 * the renderer uses, plus the two call sites that have to obey it. Real presses, real handles and real
 * screenshots are accepted in runClient - nothing here claims a frame was drawn.</p>
 */
class HudPressPriorityAndDepthTest {

    private static final Path SOURCE = Path.of("src/main/java/com/tactical/neoforge/client");

    // ------------------------------------------------------- press ownership

    @Test
    void anInterfaceControlOwnsItsPressOnEverySurface() {
        for (BattleInputPolicy.Surface surface : BattleInputPolicy.Surface.values()) {
            boolean page = surface == BattleInputPolicy.Surface.TACTICAL;
            assertEquals(page ? BattleInputPolicy.PressOwner.PAGE : BattleInputPolicy.PressOwner.HUD,
                    BattleInputPolicy.pressOwner(true, surface),
                    "a visible interface control is the upper layer under the pointer on " + surface);
        }
    }

    @Test
    void aPointNoControlClaimsKeepsTheOldOrder() {
        for (BattleInputPolicy.Surface surface : BattleInputPolicy.Surface.values()) {
            assertEquals(BattleInputPolicy.PressOwner.LOWER, BattleInputPolicy.pressOwner(false, surface),
                    "without a control the hotbar and the world keep their order on " + surface);
        }
    }

    @Test
    void thePressListenerAsksTheInterfaceBeforeTheQuickBar() throws IOException {
        String input = source("BattleScreenInput.java");
        int listener = input.indexOf("public static void onMouseButton");
        assertTrue(listener >= 0, "the press listener must exist");
        String hook = input.substring(listener);
        int claims = hook.indexOf("NativeHud.claimsPress(");
        int hotbar = hook.indexOf("BattleUiRegions.hotbarSlot()");
        assertTrue(claims >= 0, "the press listener must ask the interface what it claims");
        assertTrue(hotbar >= 0, "the press listener must still handle the quick bar");
        assertTrue(claims < hotbar, "the interface controls must be asked before the hotbar band");
        // The two answers of the policy both have to be acted on, not just computed.
        assertTrue(hook.contains("PressOwner.HUD"), "a claimed press must be delivered when no page can");
        assertTrue(hook.contains("PressOwner.PAGE"), "an open page's own delivery must not be pre-empted");
        assertTrue(hook.contains("NativeHud.click("), "the claimed press still goes through the HUD entry");
        assertTrue(hook.contains("NativeHud.release("), "the release half of a HUD gesture stays first");
        assertTrue(hook.indexOf("NativeHud.release(") < hotbar,
                "a release that ends a HUD gesture must be offered before the hotbar claims it");
    }

    @Test
    void onlyAReallyOperableControlClaimsThePress() throws IOException {
        String hud = source("ui/hud/NativeHud.java");
        int method = hud.indexOf("public static boolean claimsPress(");
        assertTrue(method >= 0, "the HUD must expose what a press would operate");
        String body = hud.substring(method, hud.indexOf("\n    }", method));
        assertTrue(body.contains("hidden(minecraft)") && body.contains("chatOpen(minecraft)"),
                "a hidden or chat-covered HUD owns nothing");
        assertTrue(body.contains("layout == null"), "a frame that was never laid out owns nothing");
        assertTrue(body.contains("handleAt("), "a size handle is an operable control");
        assertTrue(body.contains("journalToggle()"), "the journal toggle is an operable control");
    }

    // --------------------------------------------------------------- depth

    @Test
    void theHudFloatingLayerSitsBetweenThePortraitsAndTheModalOverlay() {
        assertTrue(TacticalSurface.PORTRAIT_DEPTH < TacticalSurface.HUD_FLOAT_DEPTH,
                "a tooltip raised from a rail portrait must be in front of that model");
        assertTrue(TacticalSurface.HUD_FLOAT_DEPTH < TacticalSurface.TOP_OVERLAY_DEPTH,
                "an open modal popover must stay in front of the HUD tooltip");
        // The portrait plane is a fact about the renderer, not a preference: Portraits pushes 400 and
        // the vanilla entity renderer another 50, which is what 450 names.
        assertTrue(TacticalSurface.PORTRAIT_DEPTH >= 450,
                "the named portrait plane must cover the 400 push plus the renderer's own 50");
    }

    @Test
    void theTooltipAndTheSizeTagAreDrawnInThatPlane() throws IOException {
        String hud = source("ui/hud/NativeHud.java");
        int render = hud.indexOf("public static void render(GuiGraphics graphics)");
        assertTrue(render >= 0, "the HUD frame entry point must exist");
        String frame = hud.substring(render, hud.indexOf("\n    }", render));
        int raised = frame.indexOf("Draw.atDepth(graphics, TacticalSurface.HUD_FLOAT_DEPTH");
        int tooltip = frame.indexOf("drawTooltip(graphics, now)");
        int tag = frame.indexOf("drawDragTag(graphics)");
        assertTrue(raised >= 0, "the tooltip layer must be raised onto the named float plane");
        assertTrue(tooltip > raised, "the tooltip is drawn inside the raised plane");
        assertTrue(tag > raised, "the size tag shares the tooltip's plane");
    }

    @Test
    void theRaisedPlaneFlushesAndRestoresThePose() throws IOException {
        String draw = source("ui/platform/Draw.java");
        int method = draw.indexOf("public static void atDepth(");
        assertTrue(method >= 0, "the depth helper lives in the one class that touches GuiGraphics");
        String body = draw.substring(method, draw.indexOf("\n    }", method));
        int firstFlush = body.indexOf("graphics.flush()");
        int push = body.indexOf("pushPose()");
        int translate = body.indexOf("pose.translate(");
        int finallyAt = body.indexOf("finally");
        int pop = body.indexOf("popPose()");
        assertTrue(firstFlush >= 0 && push > firstFlush, "everything drawn earlier is submitted first");
        assertTrue(translate > push, "the plane is raised on a pushed pose");
        assertTrue(finallyAt > translate && pop > finallyAt,
                "the pose is restored in a finally, so a throwing layer cannot leave it raised");
        assertTrue(body.indexOf("graphics.flush()", firstFlush + 1) > translate,
                "the raised batch is submitted before the pose is restored");
    }

    private static String source(String relative) throws IOException {
        Path file = SOURCE.resolve(relative);
        assertTrue(Files.isReadable(file), "missing production source " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
