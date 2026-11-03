package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.BattleClientState;
import com.tactical.neoforge.client.ui.command.CommandMenuModel;
import com.tactical.neoforge.client.ui.platform.ClientText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command panel while a forced fall owns the encounter.
 *
 * <p>The server refuses every command until the last falling unit has landed. The panel therefore has to
 * be readable as "nothing to click yet" and has to say why, instead of offering rows whose only possible
 * outcome is a rejection line in the journal. The flag is the server's own snapshot field, so this state
 * survives a page that was just opened and a client whose animation has already finished.</p>
 */
class CommandMenuWaitingTest {

    @AfterEach
    void restore() {
        BattleClientState.clear();
    }

    @Test
    void everyRowIsUnavailableWhileTheFallRuns() {
        BattleClientState.active = true;
        BattleClientState.ap = 150;
        BattleClientState.maxAp = 150;
        BattleClientState.terrainFalling = true;

        CommandMenuModel model = CommandMenuModel.snapshot(null);

        assertFalse(model.rows.isEmpty(), "the panel still lists its rows, it just offers none of them");
        for (CommandMenuModel.Row row : model.rows) {
            if ("character".equals(row.action())) {
                // The character page is a view, not an action: a forced fall does not take it away.
                assertTrue(row.enabled(), "the character page stays reachable during a fall");
                continue;
            }
            assertFalse(row.enabled(), row.action() + " must not look available during a fall");
            assertNotNull(row.name(), row.action() + " keeps its label");
            assertFalse(row.name().isEmpty(), row.action() + " keeps its label");
        }
        assertFalse(ClientText.get(ClientText.Keys.COMMAND_FOOTER_FALLING).isBlank(),
                "the waiting note needs a shipped wording");
        assertTrue(model.footer.contains(ClientText.Keys.COMMAND_FOOTER_FALLING),
                "the footer has to say why nothing can be clicked");
    }

    @Test
    void theRowsComeBackWhenTheLastUnitLands() {
        BattleClientState.active = true;
        BattleClientState.ap = 150;
        BattleClientState.maxAp = 150;
        BattleClientState.terrainFalling = false;

        CommandMenuModel model = CommandMenuModel.snapshot(null);

        assertNotNull(model.row("move"));
        assertTrue(model.row("move").enabled(), "an ordinary turn offers its rows again");
        assertFalse(model.footer.contains(ClientText.Keys.COMMAND_FOOTER_FALLING),
                "a landed encounter has nothing left to wait for");
    }
}
