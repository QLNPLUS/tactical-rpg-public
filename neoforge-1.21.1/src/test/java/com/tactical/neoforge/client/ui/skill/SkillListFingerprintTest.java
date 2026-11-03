package com.tactical.neoforge.client.ui.skill;

import com.tactical.neoforge.network.SkillView;
import com.tactical.neoforge.skill.SkillDefinition;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What makes the skill list rebuild: a changed set of skills, not a changed frame.
 *
 * <p>The regression this locks: the page built a zero-height list while the first server snapshot was
 * still empty and never rebuilt when the real skills arrived, because the layout cache only compared
 * the window size and the size preset.</p>
 */
class SkillListFingerprintTest {

    private static final ResourceLocation BACKSTAB = id("tactical:backstab");
    private static final ResourceLocation GUARD = id("tactical:guard");
    private static final ResourceLocation BOLT = id("tactical:bolt");

    @Test void theSameSkillsAlwaysHashTheSame() {
        List<SkillView> skills = List.of(view(BACKSTAB, 1, true), view(GUARD, 2, false));
        assertEquals(SkillListFingerprint.of(skills), SkillListFingerprint.of(skills));
        assertEquals(SkillListFingerprint.of(List.of()), SkillListFingerprint.of(List.of()));
    }

    @Test void theEmptyListDiffersFromEveryPopulatedOne() {
        long empty = SkillListFingerprint.of(List.of());
        assertNotEquals(empty, SkillListFingerprint.of(List.of(view(BACKSTAB, 1, true))));
        assertNotEquals(empty, SkillListFingerprint.of(List.of(view(GUARD, 1, true))));
    }

    @Test void theSameCountWithDifferentSkillsRebuilds() {
        long before = SkillListFingerprint.of(List.of(view(BACKSTAB, 1, true), view(GUARD, 1, true)));
        long after = SkillListFingerprint.of(List.of(view(BACKSTAB, 1, true), view(BOLT, 1, true)));
        assertNotEquals(before, after, "a loadout swap keeps the row count but moves the rows");
    }

    @Test void theOrderLevelAndReadinessAllCount() {
        long base = SkillListFingerprint.of(List.of(view(BACKSTAB, 1, true), view(GUARD, 1, true)));
        assertNotEquals(base, SkillListFingerprint.of(List.of(view(GUARD, 1, true), view(BACKSTAB, 1, true))));
        assertNotEquals(base, SkillListFingerprint.of(List.of(view(BACKSTAB, 2, true), view(GUARD, 1, true))));
        assertNotEquals(base, SkillListFingerprint.of(List.of(view(BACKSTAB, 1, false), view(GUARD, 1, true))));
    }

    @Test void valuesThatAreOnlyDrawnDoNotTriggerARebuild() {
        // Cost, description, level text and cooldown are painted from live state; only a change that can
        // move a row may rebuild the list.
        SkillView cheap = new SkillView(BACKSTAB, "背刺", "短说明", 1, 5, SkillDefinition.Aim.ENTITY, false, false,
                true, "", 5, 1.0D, 0);
        SkillView dear = new SkillView(BACKSTAB, "背刺", "完全不同的更长的说明文本", 1, 5, SkillDefinition.Aim.ENTITY,
                false, false, true, "", 25, 40.0D, 3);
        assertEquals(SkillListFingerprint.of(List.of(cheap)), SkillListFingerprint.of(List.of(dear)));
    }

    @Test void theMixerIsStableAndSensitivePerCharacter() {
        long start = com.tactical.neoforge.client.ui.layout.Fingerprint.start();
        long a = com.tactical.neoforge.client.ui.layout.Fingerprint.text(start, "skill");
        long b = com.tactical.neoforge.client.ui.layout.Fingerprint.text(start, "skilL");
        assertNotEquals(a, b);
        assertEquals(a, com.tactical.neoforge.client.ui.layout.Fingerprint.text(start, "skill"));
        assertTrue(com.tactical.neoforge.client.ui.layout.Fingerprint.text(start, "") != start,
                "the empty string is still an input, not a missing value");
    }

    private static SkillView view(ResourceLocation id, int level, boolean ready) {
        return new SkillView(id, id.getPath(), "说明", level, 5, SkillDefinition.Aim.ENTITY, false, false, ready,
                ready ? "" : "尚未轮到该单位", 10, 2.0D, 0);
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }
}
