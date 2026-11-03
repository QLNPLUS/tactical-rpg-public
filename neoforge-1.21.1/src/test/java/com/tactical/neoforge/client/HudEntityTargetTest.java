package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.platform.ClientText;
import com.tactical.neoforge.skill.SkillDefinition;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One entity-target entry for the HUD: which modes can choose a row at all, what a row means in each of
 * them, and the rule that a press/release pair acts at most once for the identity and the command it
 * captured.
 */
class HudEntityTargetTest {

    private static final UUID SESSION = new UUID(0, 5);
    private static final UUID ENEMY = new UUID(0, 11);
    private static final UUID SELF = new UUID(0, 12);
    private static final UUID ALLY = new UUID(0, 13);

    private static HudEntityTarget.Row enemy() {
        return new HudEntityTarget.Row(ENEMY, true, true, true, false);
    }

    private static HudEntityTarget.Row self() {
        return new HudEntityTarget.Row(SELF, true, false, true, true);
    }

    private static HudEntityTarget.Row ally() {
        return new HudEntityTarget.Row(ALLY, true, false, true, false);
    }

    @Test
    void onlyTheThreeEntityModesGiveRowsAMeaning() {
        assertTrue(HudEntityTarget.entityMode(BattleMode.ATTACK, null));
        assertTrue(HudEntityTarget.entityMode(BattleMode.USE_ITEM, null));
        assertTrue(HudEntityTarget.entityMode(BattleMode.SKILL, SkillDefinition.Aim.ENTITY));
        assertTrue(HudEntityTarget.entityMode(BattleMode.SKILL, SkillDefinition.Aim.SELF));
        assertFalse(HudEntityTarget.entityMode(BattleMode.NONE, null));
        assertFalse(HudEntityTarget.entityMode(BattleMode.MOVE, null));
        assertFalse(HudEntityTarget.entityMode(BattleMode.PLACE, null));
        assertFalse(HudEntityTarget.entityMode(BattleMode.BREAK, null), "a block target is never a row");
        // A ground skill is a battlefield click: the entity's feet must never become the anchor.
        assertFalse(HudEntityTarget.entityMode(BattleMode.SKILL, SkillDefinition.Aim.GROUND));
    }

    @Test
    void groundSkillsAndPlainModesNeverActOnARow() {
        assertEquals(HudEntityTarget.Action.NONE,
                HudEntityTarget.action(BattleMode.SKILL, SkillDefinition.Aim.GROUND, enemy()));
        assertEquals(HudEntityTarget.Action.NONE, HudEntityTarget.action(BattleMode.NONE, null, enemy()));
        assertEquals(HudEntityTarget.Action.NONE, HudEntityTarget.action(BattleMode.MOVE, null, enemy()));
        assertEquals(HudEntityTarget.Action.NONE, HudEntityTarget.action(BattleMode.PLACE, null, enemy()));
        assertEquals(HudEntityTarget.Action.NONE, HudEntityTarget.action(BattleMode.BREAK, null, enemy()));
    }

    @Test
    void attackOnlyTakesMobsAndASelfAimedSkillOnlyItsOwnRow() {
        assertEquals(HudEntityTarget.Action.ATTACK, HudEntityTarget.action(BattleMode.ATTACK, null, enemy()));
        assertEquals(HudEntityTarget.Action.NONE, HudEntityTarget.action(BattleMode.ATTACK, null, ally()),
                "the server only knows battle enemies, so another member is not an attack target");
        assertEquals(HudEntityTarget.Action.SKILL,
                HudEntityTarget.action(BattleMode.SKILL, SkillDefinition.Aim.SELF, self()));
        assertEquals(HudEntityTarget.Action.NONE,
                HudEntityTarget.action(BattleMode.SKILL, SkillDefinition.Aim.SELF, ally()));
        assertEquals(HudEntityTarget.Action.SKILL,
                HudEntityTarget.action(BattleMode.SKILL, SkillDefinition.Aim.ENTITY, ally()));
        assertEquals(HudEntityTarget.Action.ITEM, HudEntityTarget.action(BattleMode.USE_ITEM, null, ally()));
    }

    @Test
    void aRowThatLeftTheEncounterIsNeverATargetEvenWhileLoaded() {
        // A loaded, alive entity that is no longer a participant must not stay clickable behind its row.
        HudEntityTarget.Row departed = new HudEntityTarget.Row(ENEMY, true, true, false, false);
        assertEquals(HudEntityTarget.Action.NONE, HudEntityTarget.action(BattleMode.ATTACK, null, departed));
        assertEquals(HudEntityTarget.Action.NONE, HudEntityTarget.action(BattleMode.USE_ITEM, null, departed));
        assertEquals(HudEntityTarget.Action.NONE,
                HudEntityTarget.action(BattleMode.SKILL, SkillDefinition.Aim.ENTITY, departed));
        assertEquals(ClientText.Keys.ENTITY_NOT_PARTICIPANT,
                HudEntityTarget.blockedReason(BattleMode.ATTACK, null, departed));
    }

    @Test
    void aDeadOrUnloadedRowIsNeverATarget() {
        HudEntityTarget.Row dead = new HudEntityTarget.Row(ENEMY, false, true, true, false);
        assertEquals(HudEntityTarget.Action.NONE, HudEntityTarget.action(BattleMode.ATTACK, null, dead));
        assertEquals(HudEntityTarget.Action.NONE, HudEntityTarget.action(BattleMode.USE_ITEM, null, dead));
        assertEquals(ClientText.Keys.ENTITY_DEAD, HudEntityTarget.blockedReason(BattleMode.ATTACK, null, dead));
        assertEquals(HudEntityTarget.Action.NONE,
                HudEntityTarget.action(BattleMode.ATTACK, null, HudEntityTarget.Row.UNKNOWN));
        assertEquals(ClientText.Keys.ENTITY_NO_TARGET,
                HudEntityTarget.blockedReason(BattleMode.ATTACK, null, HudEntityTarget.Row.UNKNOWN));
    }

    @Test
    void unavailableRowsStateWhy() {
        // The routing reports a wording key and the HUD resolves it at its own display point, so a
        // missing key can never reach the tooltip as a half-Chinese half-English sentence.
        assertEquals(ClientText.Keys.ENTITY_MOVE_GROUND, HudEntityTarget.blockedReason(BattleMode.MOVE, null, ally()));
        assertEquals(ClientText.Keys.ENTITY_PLACE_GROUND,
                HudEntityTarget.blockedReason(BattleMode.PLACE, null, ally()));
        assertEquals(ClientText.Keys.ENTITY_BREAK_GROUND,
                HudEntityTarget.blockedReason(BattleMode.BREAK, null, ally()));
        assertEquals(ClientText.Keys.ENTITY_SKILL_GROUND,
                HudEntityTarget.blockedReason(BattleMode.SKILL, SkillDefinition.Aim.GROUND, ally()));
        assertEquals(ClientText.Keys.ENTITY_NOT_ENEMY,
                HudEntityTarget.blockedReason(BattleMode.ATTACK, null, ally()));
        assertEquals(ClientText.Keys.ENTITY_SELF_ONLY,
                HudEntityTarget.blockedReason(BattleMode.SKILL, SkillDefinition.Aim.SELF, ally()));
        assertEquals("", HudEntityTarget.blockedReason(BattleMode.ATTACK, null, enemy()));
    }

    @Test
    void everyRefusalIsAKeyTheClientShips() {
        // Walking the same switches through every mode and aim: whatever comes back has to be a key this
        // class ships, and no key may leak a raw English identifier into a sentence.
        java.util.List<String> reasons = new java.util.ArrayList<>();
        for (BattleMode mode : BattleMode.values()) {
            reasons.add(HudEntityTarget.blockedReason(mode, null, ally()));
            reasons.add(HudEntityTarget.blockedReason(mode, SkillDefinition.Aim.GROUND, ally()));
        }
        for (String reason : reasons) {
            if (reason.isEmpty()) continue;
            assertTrue(ClientText.ALL_KEYS.contains(reason), reason + " has to be a shipped wording key");
            assertFalse(reason.contains(" "), "a refusal is a key, not a sentence");
        }
    }

    private static HudEntityTarget.Capture captureOfEnemy() {
        return new HudEntityTarget.Capture(ENEMY, HudEntityTarget.Action.ATTACK, SESSION, 7L, BattleMode.ATTACK,
                null, "", -1);
    }

    @Test
    void theMatchingReleaseActsExactlyOnce() {
        HudEntityTarget.Capture capture = captureOfEnemy();
        assertTrue(capture.release(HudEntityTarget.Action.ATTACK, enemy(), SESSION, 7L, BattleMode.ATTACK, null, "",
                -1));
        assertFalse(capture.release(HudEntityTarget.Action.ATTACK, enemy(), SESSION, 7L, BattleMode.ATTACK, null, "",
                -1), "the second release of the same press can never act again");
        assertTrue(capture.used());
    }

    @Test
    void aReleaseCannotActOnWhateverRowIsUnderThePointerNow() {
        // The numeric id may have been recycled: another entity under the pointer is a different target.
        HudEntityTarget.Row other = new HudEntityTarget.Row(new UUID(0, 99), true, true, true, false);
        assertFalse(captureOfEnemy().release(HudEntityTarget.Action.ATTACK, other, SESSION, 7L, BattleMode.ATTACK,
                null, "", -1));
        // Even the same row is refused once the target went down or left before the release.
        HudEntityTarget.Row dead = new HudEntityTarget.Row(ENEMY, false, true, true, false);
        assertFalse(captureOfEnemy().release(HudEntityTarget.Action.ATTACK, dead, SESSION, 7L, BattleMode.ATTACK,
                null, "", -1));
        HudEntityTarget.Row departed = new HudEntityTarget.Row(ENEMY, true, true, false, false);
        assertFalse(captureOfEnemy().release(HudEntityTarget.Action.ATTACK, departed, SESSION, 7L,
                BattleMode.ATTACK, null, "", -1));
    }

    @Test
    void aChangedModeTurnOrSlotInvalidatesThePress() {
        assertFalse(captureOfEnemy().release(HudEntityTarget.Action.ATTACK, enemy(), new UUID(0, 6), 7L,
                BattleMode.ATTACK, null, "", -1), "another encounter owns this client now");
        assertFalse(captureOfEnemy().release(HudEntityTarget.Action.ATTACK, enemy(), SESSION, 8L, BattleMode.ATTACK,
                null, "", -1), "a new turn generation invalidates the old press");
        assertFalse(captureOfEnemy().release(HudEntityTarget.Action.ATTACK, enemy(), SESSION, 7L, BattleMode.USE_ITEM,
                null, "", -1), "the mode changed under the pointer");
        HudEntityTarget.Capture item = new HudEntityTarget.Capture(ALLY, HudEntityTarget.Action.ITEM, SESSION, 7L,
                BattleMode.USE_ITEM, null, "", 3);
        assertFalse(item.release(HudEntityTarget.Action.ITEM, ally(), SESSION, 7L, BattleMode.USE_ITEM, null, "", 4),
                "another hotbar slot is another item");
        HudEntityTarget.Capture sameItem = new HudEntityTarget.Capture(ALLY, HudEntityTarget.Action.ITEM, SESSION, 7L,
                BattleMode.USE_ITEM, null, "", 3);
        assertTrue(sameItem.release(HudEntityTarget.Action.ITEM, ally(), SESSION, 7L, BattleMode.USE_ITEM, null, "",
                3));
    }

    @Test
    void aCancelledPressNeverActs() {
        HudEntityTarget.Capture capture = captureOfEnemy();
        capture.cancel();
        assertFalse(capture.release(HudEntityTarget.Action.ATTACK, enemy(), SESSION, 7L, BattleMode.ATTACK, null, "",
                -1));
    }

    /**
     * The gesture the user reported as "the roster stops scrolling": press a row, move onto the hotbar,
     * release there. The release has to end the gesture where it lands - nothing executes, and no capture
     * survives into the next wheel tick or the next press.
     */
    @Test
    void aReleaseThatLeftTheRowEndsTheGestureWithoutActing() {
        HudEntityTarget.Capture capture = captureOfEnemy();
        HudEntityTarget.Action away = HudEntityTarget.action(BattleMode.ATTACK, null, HudEntityTarget.Row.UNKNOWN);
        assertFalse(capture.release(away, HudEntityTarget.Row.UNKNOWN, SESSION, 7L, BattleMode.ATTACK, null, "", -1),
                "the pointer is no longer on the row, so nothing may execute");
        assertTrue(capture.used(), "and the gesture is spent, not left hanging");
        assertFalse(capture.release(HudEntityTarget.Action.ATTACK, enemy(), SESSION, 7L, BattleMode.ATTACK, null, "",
                -1), "the same press can never act later, for instance after the pointer returns");
    }

    @Test
    void anAimChangeIsAPressInvalidation() {
        HudEntityTarget.Capture skill = new HudEntityTarget.Capture(ALLY, HudEntityTarget.Action.SKILL, SESSION, 7L,
                BattleMode.SKILL, SkillDefinition.Aim.ENTITY, "tactical:probe_lance", -1);
        assertFalse(skill.release(HudEntityTarget.Action.SKILL, ally(), SESSION, 7L, BattleMode.SKILL,
                SkillDefinition.Aim.SELF, "tactical:probe_lance", -1),
                "the selected skill's aim changed to something else");
    }

    @Test
    void anotherSkillWithTheSameAimInvalidatesThePress() {
        // Two entity skills share mode and aim, so the chosen skill itself has to be part of the capture.
        HudEntityTarget.Capture lance = new HudEntityTarget.Capture(ALLY, HudEntityTarget.Action.SKILL, SESSION, 7L,
                BattleMode.SKILL, SkillDefinition.Aim.ENTITY, "tactical:probe_lance", -1);
        assertFalse(lance.release(HudEntityTarget.Action.SKILL, ally(), SESSION, 7L, BattleMode.SKILL,
                SkillDefinition.Aim.ENTITY, "tactical:probe_shot", -1),
                "a press made for one entity skill must not cast the other one");
        HudEntityTarget.Capture again = new HudEntityTarget.Capture(ALLY, HudEntityTarget.Action.SKILL, SESSION, 7L,
                BattleMode.SKILL, SkillDefinition.Aim.ENTITY, "tactical:probe_lance", -1);
        assertTrue(again.release(HudEntityTarget.Action.SKILL, ally(), SESSION, 7L, BattleMode.SKILL,
                SkillDefinition.Aim.ENTITY, "tactical:probe_lance", -1));
    }
}
