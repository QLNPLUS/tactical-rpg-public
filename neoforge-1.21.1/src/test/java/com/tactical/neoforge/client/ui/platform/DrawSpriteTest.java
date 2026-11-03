package com.tactical.neoforge.client.ui.platform;

import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.character.CharacterLayout;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three picture symbols: the shipped files and the placement that puts them in their control.
 *
 * <p>The symbols used to be font characters, so their position was decided by the font's baseline and by the
 * language's glyph metrics. They are the user's own 8x8 pictures now: the files must stay byte-identical to
 * what was handed over, and the placement must be pure arithmetic over the box. There is deliberately no
 * {@code Font} in that arithmetic, and these checks say so by never supplying one.</p>
 *
 * <p>The defect this file now pins: the placement divided the leftover space by two <em>as integers</em>, so
 * a box whose width minus the artwork is odd - including the actual 10 px control at 100% - dropped the
 * symbol half a pixel towards the top left on both axes. The user saw that as a {@code +}/{@code −} that was
 * not centred. The artwork's centre is the box's centre now, and the origin is snapped onto whole screen
 * pixels afterwards, so nothing is ever drawn between two screen pixels.</p>
 */
class DrawSpriteTest {

    private static final Draw.Sprite[] SYMBOLS = {Draw.ICON_MINUS, Draw.ICON_PLUS, Draw.ICON_CLOSE};

    @Test
    void theShippedSymbolsAreTheUsersEightByEightTransparentPictures() throws IOException {
        for (Draw.Sprite sprite : SYMBOLS) {
            assertEquals("tactical", sprite.texture().getNamespace());
            assertTrue(sprite.texture().getPath().startsWith("textures/gui/icons/"),
                    "the symbols live in the icon folder, got " + sprite.texture().getPath());
            assertTrue(sprite.texture().getPath().endsWith(".png"), "a texture path names its file");
            BufferedImage image = load(sprite);
            assertEquals(8, image.getWidth(), sprite.texture().getPath() + " is eight pixels wide");
            assertEquals(8, image.getHeight(), sprite.texture().getPath() + " is eight pixels tall");
            assertTrue(image.getColorModel().hasAlpha(),
                    sprite.texture().getPath() + " keeps its alpha channel");
            // The artwork really is where the sprite says it is, and the rest of the canvas is transparent.
            int opaqueInside = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    boolean inked = (image.getRGB(x, y) >>> 24) > 0x10;
                    boolean declared = x >= sprite.u() && x < sprite.u() + sprite.w()
                            && y >= sprite.v() && y < sprite.v() + sprite.h();
                    if (declared && inked) opaqueInside++;
                    if (!declared) {
                        assertTrue(!inked, sprite.texture().getPath() + " has ink outside its artwork box at "
                                + x + "," + y);
                    }
                }
            }
            assertTrue(opaqueInside > 0, sprite.texture().getPath() + " really carries a symbol");
        }
    }

    /**
     * The artwork's centre is the box's centre at every size, including the odd ones.
     *
     * <p>Box sizes 7..18 cover the character controls and larger shared symbol buttons. When the leftover
     * space is odd, the exact origin is a half GUI pixel; truncating it was the reported bug.</p>
     */
    @Test
    void theArtworkIsCentredOnTheBoxAtEverySize() {
        for (Draw.Sprite sprite : SYMBOLS) {
            for (int size = 7; size <= 18; size++) {
                Rect box = Rect.of(100, 40, size, size);
                Draw.SpritePlacement placed = Draw.spritePlacement(sprite, box);
                String where = sprite.texture().getPath() + " in a " + size + " px box";
                assertEquals(box.x() + box.w() / 2.0D, placed.centreX(), 1.0E-9D, where + " is centred horizontally");
                assertEquals(box.y() + box.h() / 2.0D, placed.centreY(), 1.0E-9D, where + " is centred vertically");
                assertTrue(placed.x() >= box.x() && placed.x() + placed.w() <= box.right(),
                        where + " stays inside the box");
                assertTrue(placed.y() >= box.y() && placed.y() + placed.h() <= box.bottom(),
                        where + " stays inside the box");
            }
        }
    }

    /**
     * An odd box still leaves the artwork on a whole GUI pixel only when the leftover space is even.
     *
     * <p>This is the anti-regression for the truncation itself: 14 and 16 px boxes must stay exactly as they
     * were (a whole pixel), and 13/15 px boxes must be a half pixel <em>and</em> centred - both cannot be true
     * for a truncated origin.</p>
     */
    @Test
    void anOddLeftoverIsHalfAPixelAndAnEvenOneIsNot() {
        for (Draw.Sprite sprite : SYMBOLS) {
            for (int size = 7; size <= 20; size++) {
                Draw.SpritePlacement placed = Draw.spritePlacement(sprite, Rect.of(0, 0, size, size));
                String where = sprite.texture().getPath() + " in a " + size + " px box";
                boolean wholePixelX = (size - placed.w()) % 2 == 0;
                boolean wholePixelY = (size - placed.h()) % 2 == 0;
                assertEquals(wholePixelX, placed.x() == Math.floor(placed.x()),
                        where + ": a leftover of " + (size - placed.w()) + " px decides the half pixel");
                assertEquals(wholePixelY, placed.y() == Math.floor(placed.y()), where);
                // Exactly half a pixel, never more: the artwork cannot be moved further and stay centred.
                assertEquals(0.0D, Math.abs(placed.centreX() - size / 2.0D), 1.0E-9D, where + " is centred");
            }
        }
    }

    /**
     * The reported case, stated as the old arithmetic would get it wrong.
     *
     * <p>The real 8/10/12 px controls and larger 13/15 px boxes leave odd space around their artwork. The old
     * placement divided that space by two as an integer, flooring the centred origin - the up-left shift
     * the user saw. The new origin is half a pixel further right and down, and centred.</p>
     */
    @Test
    void theOddPresetBoxesAreNoLongerTruncatedTowardsTheTopLeft() {
        for (int size : new int[]{8, 10, 13, 15}) {
            for (Draw.Sprite sprite : SYMBOLS) {
                Draw.SpritePlacement placed = Draw.spritePlacement(sprite, Rect.of(10, 10, size, size));
                int truncatedX = 10 + (size - placed.w()) / 2;
                int truncatedY = 10 + (size - placed.h()) / 2;
                String where = sprite.texture().getPath() + " at a " + size + " px icon box";
                assertTrue(placed.x() > truncatedX, where + ": the origin moves right of the truncated one");
                assertTrue(placed.y() > truncatedY, where + ": the origin moves below the truncated one");
                assertEquals(10 + size / 2.0D, placed.centreX(), 1.0E-9D, where + " is centred horizontally");
                assertEquals(10 + size / 2.0D, placed.centreY(), 1.0E-9D, where + " is centred vertically");
            }
        }
    }

    /**
     * Drawing snaps the origin onto whole screen pixels, so a half GUI pixel is never a blurry edge.
     *
     * <p>At GUI scale 2 a half GUI pixel is one screen pixel, which makes the centring exact; at scale 1 the
     * symbol keeps whole GUI pixels (the pre-existing appearance) instead of landing between two screen pixels;
     * at scale 3 the origin is whole screen pixels and the remaining error is half a screen pixel.</p>
     */
    @Test
    void theDrawnOriginLandsOnWholeScreenPixels() {
        for (double framebuffer : new double[]{1.0D, 2.0D, 3.0D, 4.0D}) {
            for (int size = 7; size <= 18; size++) {
                Rect box = Rect.of(64, 24, size, size);
                for (Draw.Sprite sprite : SYMBOLS) {
                    Draw.SpritePlacement placed = Draw.spritePlacement(sprite, box, framebuffer);
                    String where = sprite.texture().getPath() + " at " + size + " px, GUI scale " + framebuffer;
                    double originX = placed.x() * framebuffer;
                    double originY = placed.y() * framebuffer;
                    assertEquals(Math.rint(originX), originX, 1.0E-6D, where + " x is a whole screen pixel");
                    assertEquals(Math.rint(originY), originY, 1.0E-6D, where + " y is a whole screen pixel");
                    // Half a screen pixel is the most a snapped half GUI pixel can cost.
                    double tolerance = 0.5D / framebuffer + 1.0E-9D;
                    assertEquals(box.x() + box.w() / 2.0D, placed.centreX(), tolerance,
                            where + " stays centred");
                    assertEquals(box.y() + box.h() / 2.0D, placed.centreY(), tolerance,
                            where + " stays centred");
                }
            }
        }
    }

    @Test
    void everyInterfaceSizeKeepsTheSymbolCentredOnItsButton() {
        double[] presets = {0.80D, 0.85D, 0.90D, 0.95D, 1.00D, 1.05D, 1.10D, 1.15D};
        int all = CharacterLayout.Content.mask(true, true, true, true, true, true);
        var editable = new CharacterLayout.Content(true, false, false, false, false, true, true, true, all, all);
        for (double preset : presets) {
            CharacterLayout layout = CharacterLayout.build(640, 360, preset, editable,
                    text -> text.length() * 6, List.of("Confirm points", "Discard draft", "Level up"),
                    List.of("Confirm level up", "Cancel"), 0);
            assertTrue(!layout.statRows().isEmpty(), "the layout supplies actual controls");
            if (preset == 1.00D) assertEquals(10, layout.statRows().getFirst().plus().w(),
                    "row padding constrains the actual 100% control below ICON_BUTTON's 14 px cap");
            for (CharacterLayout.StatRow row : layout.statRows()) {
                for (Rect box : List.of(row.minus(), row.plus(), layout.close())) {
                    for (Draw.Sprite sprite : SYMBOLS) {
                        Draw.SpritePlacement placed = Draw.spritePlacement(sprite, box, 2.0D);
                        String where = sprite.texture().getPath() + " at " + Math.round(preset * 100)
                                + "% in actual " + box.w() + " px control";
                        assertEquals(box.x() + box.w() / 2.0D, placed.centreX(), 1.0E-9D,
                                where + " is centred horizontally");
                        assertEquals(box.y() + box.h() / 2.0D, placed.centreY(), 1.0E-9D,
                                where + " is centred vertically");
                        assertTrue(placed.w() >= sprite.w(), where + " is not shrunk below one texel");
                        assertTrue(placed.w() <= box.w() - 2 && placed.h() <= box.h() - 2,
                                where + " keeps its one pixel margin");
                    }
                }
            }
        }
    }

    @Test
    void everySourceTexelBecomesAWholeNumberOfPixels() {
        for (Draw.Sprite sprite : SYMBOLS) {
            for (int size : new int[]{11, 14, 16, 18}) {
                Draw.SpritePlacement placed = Draw.spritePlacement(sprite, Rect.of(0, 0, size, size));
                assertTrue(placed.w() % sprite.w() == 0, sprite.texture().getPath() + " at " + size
                        + " is a whole multiple of its artwork width");
                assertTrue(placed.h() % sprite.h() == 0, sprite.texture().getPath() + " at " + size
                        + " is a whole multiple of its artwork height");
                assertEquals(placed.w() / sprite.w(), placed.h() / sprite.h(),
                        "one scale for both axes, so the picture is never stretched");
            }
        }
    }

    /**
     * The symbol's box is decided by the control's rectangle alone.
     *
     * <p>The old version measured the glyph through the font: two languages with different glyph widths placed
     * the same button's symbol differently, and a change of the type size moved it off the button's centre. The
     * same box now always produces the same placement, whatever the text around it measures.</p>
     */
    @Test
    void thePlacementDependsOnTheBoxAndNothingElse() {
        Rect box = Rect.of(12, 34, 14, 14);
        Draw.SpritePlacement first = Draw.spritePlacement(Draw.ICON_PLUS, box);
        Draw.SpritePlacement second = Draw.spritePlacement(Draw.ICON_PLUS, box);
        assertEquals(first, second);
        // Two boxes of the same size at different origins produce the same size, i.e. no font or language term.
        assertEquals(first.w(), Draw.spritePlacement(Draw.ICON_PLUS, Rect.of(300, 200, 14, 14)).w());
        // The minus and the plus share the same dark round base, so their symbols share one scale too.
        assertEquals(first.w() / Draw.ICON_PLUS.w(),
                Draw.spritePlacement(Draw.ICON_CLOSE, box).w() / Draw.ICON_CLOSE.w(),
                "the two stepper controls draw at one scale");
        // The reported rectangle is the whole-pixel one the drawn pixels cover.
        Rect reported = first.pixelRect();
        assertTrue(reported.w() == first.w() && reported.h() == first.h());
        assertTrue(reported.contains(first.centreX(), first.centreY()), "the centre is inside the drawn rectangle");
    }

    private static BufferedImage load(Draw.Sprite sprite) throws IOException {
        String path = "/assets/" + sprite.texture().getNamespace() + "/" + sprite.texture().getPath();
        try (InputStream stream = DrawSpriteTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "missing shipped texture " + path);
            BufferedImage image = ImageIO.read(stream);
            assertNotNull(image, "unreadable shipped texture " + path);
            return image;
        }
    }
}
