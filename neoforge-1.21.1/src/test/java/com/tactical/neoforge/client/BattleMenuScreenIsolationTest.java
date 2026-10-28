package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The battle menu may close its own page and nothing else.
 *
 * <p>Every tactical AUI page is a {@code TacticalScreen}, including the nearby list. Closing "the
 * tactical screen" whenever there is no battle - or whenever it is not the local player's turn -
 * closed a list the player had just opened, on the very next client tick.</p>
 */
class BattleMenuScreenIsolationTest {

    @Test
    void onlyTheBattleMenuPageBelongsToTheBattleMenu() {
        assertTrue(BattleMenu.ownsPath(BattleMenu.PAGE), "the menu owns its own page");
        assertFalse(BattleMenu.ownsPath(NearbyBattlesScreen.PAGE),
                "the nearby list is a tactical screen too, and must survive a battle tick");
        assertFalse(BattleMenu.ownsPath("tactical/screens/battle_facing.html"));
        assertFalse(BattleMenu.ownsPath("tactical/overlays/battle_log.html"));
        assertFalse(BattleMenu.ownsPath("tactical/screens/inventory.html"));
        assertFalse(BattleMenu.ownsPath(null) && true, "a missing document owns nothing");
        assertFalse(BattleMenu.ownsPath(""), "an empty path owns nothing");
    }

    @Test
    void theMenuPageIsNotSharedWithTheOtherPages() {
        assertTrue(BattleMenu.PAGE.endsWith("battle_menu.html"), "the menu page identity is explicit: " + BattleMenu.PAGE);
        assertFalse(BattleMenu.PAGE.equals(NearbyBattlesScreen.PAGE),
                "the two screens must not collapse onto one identity");
    }
}
