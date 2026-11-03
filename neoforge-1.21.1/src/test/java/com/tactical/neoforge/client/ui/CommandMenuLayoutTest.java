package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.command.CommandMenuLayout;
import com.tactical.neoforge.client.ui.layout.Box;
import com.tactical.neoforge.client.ui.layout.HitTest;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Geometry of the command panel: anchor, presets, small-window degradation and disjoint hit areas. */
class CommandMenuLayoutTest {

    private static final int STANDARD_WIDTH = 640;
    private static final int STANDARD_HEIGHT = 360;

    @Test
    void thePanelSitsOnTheTopRightEdgeAndKeepsTheEdgeDistanceFixed() {
        CommandMenuLayout layout = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 1.0D, false);
        Rect panel = layout.panel();
        assertEquals(Scale.EDGE, panel.y(), "6px from the top edge at every preset");
        assertEquals(STANDARD_WIDTH - Scale.EDGE, panel.right(), "6px from the right edge at every preset");
        assertEquals(162, panel.w(), "the standard panel is 162px wide at 100%");
        assertTrue(panel.bottom() <= STANDARD_HEIGHT - Scale.EDGE, "the panel must fit above the bottom edge");
        assertFalse(layout.narrow());
    }

    @Test
    void theEdgeDistanceDoesNotScaleButThePanelWidthDoes() {
        // A 1280x720 GUI keeps the standard form at every preset, so only the preset varies here.
        for (double preset : new double[]{0.8D, 0.85D, 1.0D, 1.05D, 1.15D}) {
            CommandMenuLayout layout = CommandMenuLayout.compute(1280, 720, preset, false);
            Rect panel = layout.panel();
            assertFalse(layout.narrow(), "1280x720 of design space stays standard at " + preset);
            assertEquals(Scale.EDGE, panel.y(), "edge distance is fixed at " + preset);
            assertEquals(1280 - Scale.EDGE, panel.right(), "right anchor is fixed at " + preset);
            assertEquals(Scale.px(162, preset), panel.w(), "panel width follows the preset at " + preset);
        }
    }

    @Test
    void aSmallWindowDegradesToTheNarrowPanel() {
        CommandMenuLayout layout = CommandMenuLayout.compute(427, 240, 1.0D, false);
        assertTrue(layout.narrow(), "854x480 at GUI scale 2 is the small-window case");
        assertEquals(112, layout.panel().w(), "the narrow panel is 112px wide at 100%");
        assertEquals(18, layout.rowHeight(), "narrow rows drop to 18px");
        assertTrue(layout.panel().bottom() <= 240, "the narrow panel still fits a 240px tall GUI");
        assertEquals(0, layout.rect(CommandMenuLayout.FOOTER).h(), "the narrow panel drops the footer");
        assertEquals(0, layout.rect(CommandMenuLayout.ACTOR).h(),
                "the narrow panel turns the actor line into the header");
    }

    @Test
    void aLargePresetOnAShortWindowDegradesToTheNarrowPanelAndStillFits() {
        CommandMenuLayout layout = CommandMenuLayout.compute(640, 300, 1.15D, false);
        assertTrue(layout.narrow(), "a 300px tall GUI cannot hold the standard or the tight panel");
        Rect panel = layout.panel();
        assertTrue(panel.bottom() <= 360, "the panel must never run off the bottom of the screen");
        assertTrue(panel.y() >= Scale.EDGE && panel.right() <= 640 - Scale.EDGE);
        assertTrue(panel.bottom() <= 300, "the panel stays inside the shorter window");
        Rect hotbar = Rect.of(320 - 95, 300 - 40, 182 + 8, 40);
        assertFalse(panel.intersects(hotbar), "the panel must never cover the hotbar and hunger area");
    }

    @Test
    void everyActionRowIsInsideThePanelAndRowsDoNotOverlap() {
        CommandMenuLayout layout = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 1.0D, false);
        Rect panel = layout.panel();
        Rect previous = null;
        for (String action : CommandMenuLayout.ACTIONS) {
            Rect row = layout.rect(CommandMenuLayout.rowId(action));
            assertTrue(row.w() > 0 && row.h() > 0, action + " has geometry");
            assertTrue(row.y() >= panel.y() && row.bottom() <= panel.bottom(), action + " stays inside the panel");
            assertTrue(row.x() >= panel.x() && row.right() <= panel.right(), action + " stays inside the panel");
            if (previous != null) {
                assertTrue(row.y() >= previous.bottom(), action + " starts below the previous row");
            }
            previous = row;
        }
    }

    @Test
    void theGearAndRowsDoNotClaimTheSamePixel() {
        CommandMenuLayout layout = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 1.0D, false);
        Rect gear = layout.rect(CommandMenuLayout.GEAR);
        for (String action : CommandMenuLayout.ACTIONS) {
            assertFalse(gear.intersects(layout.rect(CommandMenuLayout.rowId(action))),
                    "the gear must not overlap " + action);
        }
    }

    @Test
    void thePopoverIsRightAlignedUnderTheGearAndWinsTheHitTest() {
        CommandMenuLayout open = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 1.0D, true);
        Rect popover = open.rect(CommandMenuLayout.POPOVER);
        Rect gear = open.rect(CommandMenuLayout.GEAR);
        assertTrue(popover.w() > 0, "the popover has geometry when it is open");
        assertEquals(open.panel().right(), popover.right(), "the popover is aligned with the panel's right edge");
        assertTrue(popover.y() >= gear.bottom(), "the popover drops below the gear");

        for (String id : new String[]{CommandMenuLayout.POP_MINUS, CommandMenuLayout.POP_PLUS,
                CommandMenuLayout.POP_RESET, CommandMenuLayout.POP_LEGEND, CommandMenuLayout.POP_CLOSE}) {
            Rect control = open.rect(id);
            assertTrue(control.w() > 0 && control.h() > 0, id + " has geometry");
            assertTrue(popover.contains(control.x() + 0.5D, control.y() + 0.5D), id + " sits inside the popover");
        }

        Rect close = open.rect(CommandMenuLayout.POP_CLOSE);
        Box hit = HitTest.topmost(open.hitOrder(), close.x() + close.w() / 2.0D, close.y() + close.h() / 2.0D);
        assertNotNull(hit);
        assertEquals(CommandMenuLayout.POP_CLOSE, hit.id(), "the popover layer answers before the page");

        CommandMenuLayout closed = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 1.0D, false);
        assertEquals(0, closed.popoverBoxes().size(), "a closed popover has no hit areas");
    }

    @Test
    void theTightFormKeepsEveryDescriptionWeaponAndFooterRow() {
        for (double preset : new double[]{1.05D, 1.10D, 1.15D}) {
            CommandMenuLayout layout = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, preset, false);
            assertFalse(layout.narrow(), preset + " of 640x360 still holds the whole panel");
            assertTrue(layout.tight(), preset + " uses the tight form, not the standard one");
            Rect panel = layout.panel();
            assertTrue(panel.bottom() <= STANDARD_HEIGHT - Scale.EDGE, "the panel stays inside the window");
            assertTrue(panel.bottom() <= 354, "the panel's bottom edge stays at or above 354 at " + preset);
            assertEquals(22 * layout.scale(), layout.rowHeight(), 1.0D,
                    "tight rows keep the standard 22px height at " + preset);
            assertTrue(layout.rect(CommandMenuLayout.WEAPON).h() > 0, "the weapon row is present at " + preset);
            assertTrue(layout.rect(CommandMenuLayout.FOOTER).h() > 0, "the footer is present at " + preset);
            assertTrue(layout.rect(CommandMenuLayout.FOOTER_RULE).h() > 0);
            // Every row the viewport shows keeps a second line: the description is what the tight form
            // preserves. When the window cannot hold all of them the action area scrolls instead of
            // shrinking the type, so the last row may legitimately be out of sight here.
            for (String action : layout.visibleActions()) {
                Rect row = layout.rect(CommandMenuLayout.rowId(action));
                assertTrue(row.h() >= 2 * Math.round(9 * layout.scale()),
                        action + " has room for its name and description at " + preset);
            }
            assertTrue(layout.visibleRows() == CommandMenuLayout.ACTIONS.size() || layout.rowsScrollable(),
                    preset + ": every row is shown, or the viewport scrolls to the rest");
        }
    }

    @Test
    void theEndButtonsAreDisabledAtTheirEndOfTheRangeOnly() {
        CommandMenuLayout middle = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 1.0D, true, 0.8D, 100);
        assertTrue(middle.minusEnabled());
        assertTrue(middle.plusEnabled());
        // Eight controls: Close, the three entries this batch adds, the range legend, Reset size and
        // the two end buttons.
        assertEquals(8, middle.popoverBoxes().size(), "every enabled popover entry takes clicks");
        assertTrue(middle.popoverBoxes().stream().anyMatch(box -> box.id().equals(CommandMenuLayout.POP_THEME)));
        assertTrue(middle.popoverBoxes().stream()
                .anyMatch(box -> box.id().equals(CommandMenuLayout.POP_RANGE_COLOR)));
        assertTrue(middle.popoverBoxes().stream()
                .anyMatch(box -> box.id().equals(CommandMenuLayout.POP_RESET_SIZES)));

        // "Reset HUD size" takes no click once both HUDs are at their default geometry.
        CommandMenuLayout defaultSizes = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 1.0D, true,
                0.8D, 100, false);
        assertTrue(defaultSizes.popoverBoxes().stream()
                        .noneMatch(box -> box.id().equals(CommandMenuLayout.POP_RESET_SIZES)),
                "a disabled reset entry is not clickable");
        assertTrue(defaultSizes.rect(CommandMenuLayout.POP_RESET_SIZES).w() > 0,
                "but it keeps its place in the popover");

        CommandMenuLayout smallest = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 0.8D, true, 0.8D, 80);
        assertFalse(smallest.minusEnabled(), "the smaller button is disabled at 80%");
        assertTrue(smallest.plusEnabled());
        assertTrue(smallest.popoverBoxes().stream().noneMatch(box -> box.id().equals(CommandMenuLayout.POP_MINUS)),
                "a disabled button takes no click");
        Rect minus = smallest.rect(CommandMenuLayout.POP_MINUS);
        assertTrue(minus.w() > 0, "the disabled button still keeps its place in the panel");
        assertEquals(null, HitTest.topmost(smallest.hitOrder(), minus.x() + minus.w() / 2.0D,
                minus.y() + minus.h() / 2.0D), "and nothing there answers a hit");

        CommandMenuLayout largest = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, true, 0.8D, 115);
        assertTrue(largest.minusEnabled());
        assertFalse(largest.plusEnabled(), "the larger button is disabled at 115%");
        assertTrue(largest.popoverBoxes().stream().noneMatch(box -> box.id().equals(CommandMenuLayout.POP_PLUS)));
        // The disabled button keeps its rectangle, so a click there is still a click inside the popover
        // and must not be mistaken for a click outside it.
        Rect disabled = largest.rect(CommandMenuLayout.POP_PLUS);
        assertTrue(largest.rect(CommandMenuLayout.POPOVER)
                .contains(disabled.x() + disabled.w() / 2.0D, disabled.y() + disabled.h() / 2.0D));
    }

    @Test
    void theTextFloorKeepsGlyphsAtOneScreenPixelWithAGuiScaleOfOne() {
        // A GUI scale of 1 raises the floor to 1.0, so an 80% preset is still drawn at 1.0.
        CommandMenuLayout layout = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 0.8D, false, 1.0D, 80);
        assertEquals(1.0D, layout.scale(), 1.0E-9D, "the drawn scale follows the floor, not the preset");
        // The floor never raises the scale above the largest preset.
        assertEquals(Scale.MAX_PRESET, Scale.effective(0.8D, 2.0D), 1.0E-9D);
    }

    @Test
    void pageHitOrderIsGearThenRows() {
        CommandMenuLayout layout = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 1.0D, false);
        List<Box> boxes = layout.pageBoxes();
        assertEquals(1 + CommandMenuLayout.ACTIONS.size(), boxes.size());
        assertEquals(CommandMenuLayout.GEAR, boxes.get(0).id());
        Rect attack = layout.rect(CommandMenuLayout.rowId("attack"));
        Box hit = HitTest.topmost(boxes, attack.x() + attack.w() / 2.0D, attack.y() + attack.h() / 2.0D);
        assertEquals(CommandMenuLayout.rowId("attack"), hit == null ? null : hit.id());
    }
}
