package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.BattleMenu;
import com.tactical.neoforge.client.ui.command.BattleMenuScreen;
import com.tactical.neoforge.client.ui.command.CommandMenuLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command panel may close its own screen and nothing else.
 *
 * <p>Every tactical page keeps the battlefield visible and shares the tactical input surface, so
 * "close the tactical screen" whenever there is no battle - or whenever it is not the local player's
 * turn - closed a page the player had opened for their own reason on the very next client tick.</p>
 */
class BattleMenuScreenIsolationTest {

    @Test
    void onlyTheCommandPanelIdBelongsToTheBattleMenu() {
        assertTrue(BattleMenu.ownsId(BattleMenuScreen.SCREEN_ID), "the menu owns its own screen id");
        assertFalse(BattleMenu.ownsId("battle_aim"), "the aim panel is its own screen");
        assertFalse(BattleMenu.ownsId("battle_facing"), "the facing panel is its own screen");
        assertFalse(BattleMenu.ownsId("battle_skills"), "the skill list is its own screen");
        assertFalse(BattleMenu.ownsId("nearby_battles"), "the nearby list is its own screen");
        assertFalse(BattleMenu.ownsId("battle_log"), "the chronicle is not a screen at all");
        assertFalse(BattleMenu.ownsId(null), "a missing screen owns nothing");
        assertFalse(BattleMenu.ownsId(""), "an empty id owns nothing");
    }

    @Test
    void theMenuIdIsNotSharedWithOtherPages() {
        assertNotEquals(BattleMenuScreen.SCREEN_ID, "battle_aim");
        assertNotEquals(BattleMenuScreen.SCREEN_ID, "nearby_battles");
        assertTrue(CommandMenuLayout.ACTIONS.size() > 0);
    }
}
