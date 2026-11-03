package com.tactical.neoforge.client.ui.command;

import com.tactical.core.combat.BattleRules;
import com.tactical.neoforge.client.ui.platform.ClientText;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command rows are static: their names, descriptions and icons never follow the main hand.
 *
 * <p>QLN asked for the panel to stop rewriting its own rows from what the player holds, so a row now
 * states what the command is instead of what would happen with the current item. These checks run on
 * the same table the panel builds and the same icon table the painter reads: an empty hand, a block,
 * a weapon and an ordinary item all have to produce one and the same nine rows, while the prices,
 * affordability and the busy state keep following the server's snapshot.</p>
 *
 * <p>The first row is the read-only character page: it carries no price, never becomes "unavailable"
 * while the encounter is busy, and is the only row the fall gate leaves alone.</p>
 */
class CommandMenuStaticRowsTest {

    /** One price for every row, so only the hand and the AP budget can change anything. */
    private static final CommandMenuModel.Prices PRICES = new CommandMenuModel.Prices(25, 5, 12, 7);

    /** The order the player asked for: attributes, move, attack, skill, item, blocks, defend, flee, end. */
    private static final List<String> ACTIONS = List.of("character", "move", "attack", "skill", "tool", "block",
            "defend", "flee", "end");

    private static final int TOOL_ROW = 4;
    private static final int BLOCK_ROW = 5;

    @Test
    void theNineRowsKeepTheirOrder() {
        assertEquals(ACTIONS, CommandMenuModel.rows(150, PRICES, false, false).stream()
                .map(CommandMenuModel.Row::action).toList());
        assertEquals(ACTIONS, CommandMenuLayout.ACTIONS, "the layout and the model share one order");
    }

    @Test
    void thePresentationDoesNotFollowTheMainHand() {
        // The empty hand, an ordinary item and a weapon all reach this method as "not a block"; a block
        // reaches it as true. Both calls have to say the same thing about every row.
        List<CommandMenuModel.Row> withoutBlock = CommandMenuModel.rows(150, PRICES, false, false);
        List<CommandMenuModel.Row> withBlock = CommandMenuModel.rows(150, PRICES, true, false);

        for (int index = 0; index < ACTIONS.size(); index++) {
            String action = ACTIONS.get(index);
            CommandMenuModel.Row free = withoutBlock.get(index);
            CommandMenuModel.Row held = withBlock.get(index);
            assertEquals(action, free.action());
            assertEquals(action, held.action());
            assertEquals(free.name(), held.name(), action + " keeps its name whatever is held");
            assertEquals(free.description(), held.description(), action + " keeps its description");
            assertFalse(free.name().isEmpty(), action + " has a shipped name");
            assertFalse(free.description().isEmpty(), action + " has a shipped description");
        }
    }

    @Test
    void everyRowDrawsItsOwnShippedWording() {
        List<CommandMenuModel.Row> rows = CommandMenuModel.rows(150, PRICES, false, false);
        String[][] expected = {
                {"character", ClientText.Keys.COMMAND_ROW_CHARACTER, ClientText.Keys.COMMAND_ROW_CHARACTER_DESC},
                {"move", ClientText.Keys.COMMAND_ROW_MOVE, ClientText.Keys.COMMAND_ROW_MOVE_DESC},
                {"attack", ClientText.Keys.COMMAND_ROW_ATTACK, ClientText.Keys.COMMAND_ROW_ATTACK_DESC},
                {"skill", ClientText.Keys.COMMAND_ROW_SKILL, ClientText.Keys.COMMAND_ROW_SKILL_DESC},
                {"tool", ClientText.Keys.COMMAND_ROW_TOOL, ClientText.Keys.COMMAND_ROW_TOOL_DESC},
                {"block", ClientText.Keys.COMMAND_ROW_BLOCK, ClientText.Keys.COMMAND_ROW_BLOCK_DESC},
                {"defend", ClientText.Keys.COMMAND_ROW_DEFEND, ClientText.Keys.COMMAND_ROW_DEFEND_DESC},
                {"flee", ClientText.Keys.COMMAND_ROW_FLEE, ClientText.Keys.COMMAND_ROW_FLEE_DESC},
                {"end", ClientText.Keys.COMMAND_ROW_END, ClientText.Keys.COMMAND_ROW_END_DESC},
        };
        assertEquals(expected.length, rows.size(), "the expected table covers every row");
        for (int index = 0; index < expected.length; index++) {
            CommandMenuModel.Row row = rows.get(index);
            String action = expected[index][0];
            assertEquals(ClientText.get(expected[index][1]), row.name(), action + " draws its own name");
            assertEquals(ClientText.get(expected[index][2]), row.description(),
                    action + " draws its own description");
        }
    }

    /** The character row is a view: no price, no badge, and it stays available while the encounter is busy. */
    @Test
    void theCharacterRowOnlyOpensThePage() {
        CommandMenuModel.Row character = CommandMenuModel.rows(150, PRICES, false, false).get(0);

        assertEquals("", character.cost(), "viewing the character costs no AP");
        assertFalse(character.affordable(), "a free row is not an affordable action");
        assertTrue(character.enabled(), "the character page can be opened");
        assertEquals(ClientText.get(ClientText.Keys.COMMAND_ROW_CHARACTER_DESC), character.description());
        assertTrue(CommandMenuModel.rows(0, PRICES, false, false).get(0).enabled(),
                "it is reachable with no AP left");
    }

    @Test
    void theItemRowNoLongerDescribesTheHand() {
        // The two mode-specific lines still ship, but the panel must not draw either of them any more.
        String free = CommandMenuModel.rows(150, PRICES, false, false).get(TOOL_ROW).description();
        String held = CommandMenuModel.rows(150, PRICES, true, false).get(TOOL_ROW).description();

        assertEquals(ClientText.get(ClientText.Keys.COMMAND_ROW_TOOL_DESC), free);
        assertEquals(free, held, "the item row does not switch wording for a block in hand");
        assertFalse(held.equals(ClientText.get(ClientText.Keys.COMMAND_ROW_TOOL_BLOCK_DESC)),
                "the block-specific item line is no longer what the panel shows");
    }

    @Test
    void theBlockRowNamesBothMeaningsInOneLine() {
        String free = CommandMenuModel.rows(150, PRICES, false, false).get(BLOCK_ROW).description();
        String held = CommandMenuModel.rows(150, PRICES, true, false).get(BLOCK_ROW).description();

        assertEquals(ClientText.get(ClientText.Keys.COMMAND_ROW_BLOCK_DESC), free);
        assertEquals(free, held, "one line covers placing and breaking");
        assertFalse(free.equals(ClientText.get(ClientText.Keys.COMMAND_ROW_BLOCK_PLACE_DESC)));
        assertFalse(free.equals(ClientText.get(ClientText.Keys.COMMAND_ROW_BLOCK_BREAK_DESC)));
    }

    @Test
    void theIconsAreFixedPerRow() {
        String[][] expected = {
                {"character", "minecraft:book"},
                {"move", "minecraft:iron_boots"},
                {"attack", "minecraft:iron_sword"},
                {"skill", "minecraft:enchanted_book"},
                {"tool", "minecraft:potion"},
                {"block", "minecraft:iron_pickaxe"},
                {"defend", "minecraft:iron_chestplate"},
                {"flee", "minecraft:feather"},
                {"end", "minecraft:clock"},
        };
        Set<String> distinct = new LinkedHashSet<>();
        for (String[] pair : expected) {
            String id = CommandMenuModel.rowIconId(pair[0]);
            assertEquals(pair[1], id, pair[0] + " has its own fixed icon");
            assertTrue(distinct.add(id), pair[0] + " draws an icon of its own");
        }
        assertEquals("", CommandMenuModel.rowIconId("something new"), "an unknown row draws nothing");
        for (String action : ACTIONS) {
            assertFalse(CommandMenuModel.rowIconId(action).isEmpty(), action + " draws something");
        }
    }

    @Test
    void holdingABlockOnlyGreysTheItemRow() {
        CommandMenuModel.Row freeItem = CommandMenuModel.rows(150, PRICES, false, false).get(TOOL_ROW);
        CommandMenuModel.Row heldItem = CommandMenuModel.rows(150, PRICES, true, false).get(TOOL_ROW);

        assertTrue(freeItem.enabled(), "an ordinary item can be used");
        assertTrue(freeItem.affordable(), "the turn can pay for it");
        assertFalse(heldItem.enabled(), "the server refuses the item command for a block");
        assertFalse(heldItem.affordable(), "a refused command is not shown as payable");
        assertEquals(PRICES.item() + " AP", heldItem.cost(), "the row still quotes the item price");
    }

    @Test
    void theBlockRowKeepsQuotingTheActionItWouldReallyTake() {
        // The two prices are configurably different, so a static description must not turn the quote
        // into a static one: placing costs the place price, breaking costs the break price.
        CommandMenuModel.Row breaking = CommandMenuModel.rows(150, PRICES, false, false).get(BLOCK_ROW);
        CommandMenuModel.Row placing = CommandMenuModel.rows(150, PRICES, true, false).get(BLOCK_ROW);

        assertEquals(PRICES.breakCost() + " AP", breaking.cost());
        assertEquals(PRICES.place() + " AP", placing.cost());
        assertTrue(breaking.affordable(), "30 AP covers a 7 AP break");
        assertTrue(placing.affordable(), "30 AP covers a 12 AP place");

        CommandMenuModel.Row poor = CommandMenuModel.rows(9, PRICES, true, false).get(BLOCK_ROW);
        assertEquals(PRICES.place() + " AP", poor.cost(), "the price is quoted even when it is unaffordable");
        assertFalse(poor.affordable(), "9 AP cannot pay for a 12 AP placement");
    }

    @Test
    void thePricesComeFromTheSnapshot() {
        CommandMenuModel.Prices other = new CommandMenuModel.Prices(40, 11, 20, 3);
        List<CommandMenuModel.Row> rows = CommandMenuModel.rows(50, other, false, false);

        assertEquals("40 AP", rows.get(2).cost());
        assertEquals("11 AP", rows.get(TOOL_ROW).cost());
        assertEquals("3 AP", rows.get(BLOCK_ROW).cost());
        assertEquals(CommandMenuModel.DEFEND_COST + " AP", rows.get(6).cost());
        assertEquals(BattleRules.FLEE_COST + " AP", rows.get(7).cost());
        assertEquals("", rows.get(8).cost(), "ending the turn costs nothing");
        assertTrue(rows.get(2).affordable(), "50 AP covers a 40 AP attack");
    }

    /**
     * A forced fall takes every command away but one.
     *
     * <p>Nobody may act while units are still landing, so each command row is greyed instead of promising an
     * action the server will refuse. The character row is left alone: it performs nothing, so the busy gate
     * that owns the encounter does not apply to reading a page.</p>
     */
    @Test
    void aForcedFallOnlyTakesTheCommandsAway() {
        List<CommandMenuModel.Row> running = CommandMenuModel.rows(150, PRICES, true, false);
        List<CommandMenuModel.Row> waiting = CommandMenuModel.rows(150, PRICES, true, true);

        for (int index = 0; index < ACTIONS.size(); index++) {
            CommandMenuModel.Row row = waiting.get(index);
            String action = ACTIONS.get(index);
            assertEquals(action, row.action());
            assertEquals(running.get(index).name(), row.name(), "the label survives the fall");
            assertEquals(running.get(index).description(), row.description(), "so does the description");
            assertEquals(running.get(index).cost(), row.cost(), "and the price the server quoted");
            if ("character".equals(action)) {
                assertTrue(row.enabled(), "the character page stays reachable during a fall");
            } else {
                assertFalse(row.enabled(), row.action() + " must not look available during a fall");
            }
        }
    }
}
