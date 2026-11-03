package com.tactical.neoforge.network;

import com.tactical.neoforge.skill.SkillDefinition;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skill channel's own version and the page contract that the version protects.
 *
 * <p>Protocol 4 published the state as pages of one complete snapshot and moved name/description/reason
 * to bounded component frames, so the packet layout changed on this channel alone and the version is
 * bumped here instead of relying on the main protocol: a peer that speaks skill protocol 3 is refused by
 * name rather than silently misreading the header.</p>
 *
 * <p>The native component codec is only available inside a bootstrapped game, so the codec itself is
 * driven by the skill wire fixture; this class pins the version and every page invariant that can be
 * decided without a game.</p>
 */
class SkillProtocolVersionTest {

    private static final int MAX_PER_PAGE = 32;

    @Test
    void theSkillChannelHasItsOwnBumpedVersion() {
        assertEquals("4", SkillNetwork.PROTOCOL,
                "paged skill state with component texts is a wire change of its own: skill protocol 3 -> 4");
        assertNotEquals(TacticalNetwork.PROTOCOL, SkillNetwork.PROTOCOL,
                "the two channels are versioned independently on purpose");
        assertNotEquals(BattleLogNetwork.PROTOCOL, SkillNetwork.PROTOCOL);
    }

    @Test
    void anUnstampedPageCarriesNoAuthority() {
        SkillStatePacket page = page(0, 1, 1, List.of(view(1)));
        assertEquals(0L, page.membershipGeneration(), "an unstamped page carries generation 0");
        assertEquals(0L, page.membershipServerEpoch());
        assertTrue(page.membershipSession() != null, "it still names its encounter, but that is not authority");
    }

    @Test
    void aStampedPageKeepsItsContentAndNamesTheRecipient() {
        SkillStatePacket page = page(0, 1, 1, List.of(view(1))).withMembership(4242L, 9L);
        assertEquals(4242L, page.membershipServerEpoch());
        assertEquals(9L, page.membershipGeneration());
        assertEquals(1, page.skills().size(), "stamping is not a content change");
        assertEquals(7L, page.revision(), "the revision stays its own number, apart from the publication");
        assertEquals(3L, page.publication());
    }

    @Test
    void oneFullListStillFitsTheCountWithoutLosingSkills() {
        // 256 legitimate skills are never reduced: they occupy the minimum number of pages.
        assertEquals(8, SkillPacketIO.minimumPages(true, 256));
        assertEquals(1, SkillPacketIO.minimumPages(true, 1));
        assertEquals(1, SkillPacketIO.minimumPages(true, 0));
        assertEquals(1, SkillPacketIO.minimumPages(false, 0));
        assertEquals(9, SkillPacketIO.minimumPages(true, 257));
    }

    @Test
    void aPageCannotCarryMoreThanTheProtocolAllows() {
        List<SkillView> tooMany = new ArrayList<>();
        for (int index = 0; index < MAX_PER_PAGE + 1; index++) tooMany.add(view(index));
        assertThrows(IllegalArgumentException.class, () -> page(0, 3, 33, tooMany),
                "a page never exceeds the per-page skill limit");
        assertThrows(IllegalArgumentException.class, () -> page(0, 1, 33, tooMany.subList(0, MAX_PER_PAGE + 1)));
    }

    @Test
    void aPageNeverDisagreesWithItsOwnHeader() {
        assertThrows(IllegalArgumentException.class, () -> page(2, 2, 8, List.of(view(1))),
                "a page index outside the page count is refused");
        assertThrows(IllegalArgumentException.class, () -> page(0, 1, 64, List.of(view(1))),
                "one page cannot claim to hold 64 skills");
        assertThrows(IllegalArgumentException.class, () -> page(0, 1, 4, List.of()),
                "a page of a non-empty state carries skills");
        assertThrows(IllegalArgumentException.class, () -> page(0, 1, 1, List.of(view(1), view(1))),
                "the same skill twice on one page is refused");
        assertThrows(IllegalArgumentException.class, () -> page(0, 2, 1, List.of(view(1), view(2))),
                "a page count below the minimum for its total is refused");
        assertThrows(IllegalArgumentException.class, () -> page(0, 2, 0, List.of()),
                "an active state without skills is one page, not two");
        assertThrows(IllegalArgumentException.class, () -> page(1, 2, 0, List.of()),
                "the empty active page is the first and only page");
    }

    @Test
    void anActiveStateWithoutSkillsIsALegitimateState() {
        // A member who owns no skill still has MP, recovery and allied identity; removing the last skill has
        // to reach the client as an empty list instead of being refused.
        SkillStatePacket empty = new SkillStatePacket(UUID.randomUUID(), dimension(), true, 3L, 2L, 0, 1, 0,
                42.0D, 60.0D, 1.5D, List.of(), List.of(12, 13));
        assertTrue(empty.active());
        assertTrue(empty.skills().isEmpty());
        assertEquals(42.0D, empty.mp(), "the shared resource state travels with the empty list");
        assertEquals(60.0D, empty.maximum());
        assertEquals(List.of(12, 13), empty.friendlyIds(), "allied identity is not withheld either");
        assertEquals(0, empty.snapshot().skillCount());
        assertTrue(empty.snapshot().active(), "an empty active state is not the inactive contract");

        SkillStatePacket inactive = new SkillStatePacket(UUID.randomUUID(), dimension(), false, 3L, 2L, 0, 1, 0,
                0.0D, 0.0D, 0.0D, List.of(), List.of());
        assertTrue(!inactive.snapshot().active(), "inactive and empty-active are still different things");
        assertThrows(IllegalArgumentException.class, () -> new SkillStatePacket(UUID.randomUUID(), dimension(),
                        false, 3L, 2L, 0, 1, 0, 0.0D, 0.0D, 0.0D, List.of(view(1)), List.of()),
                "inactive never carries skills");
    }

    @Test
    void anInactiveStateCarriesNoSkillsAtAll() {
        SkillStatePacket inactive = new SkillStatePacket(UUID.randomUUID(), dimension(), false, 1L, 1L,
                0, 1, 0, 0.0D, 0.0D, 0.0D, List.of(), List.of());
        assertTrue(inactive.skills().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new SkillStatePacket(UUID.randomUUID(), dimension(),
                        false, 1L, 1L, 0, 1, 0, 0.0D, 0.0D, 0.0D, List.of(view(1)), List.of()),
                "the existing inactive contract keeps the skill list empty");
        assertThrows(IllegalArgumentException.class, () -> new SkillStatePacket(UUID.randomUUID(), dimension(),
                        false, 1L, 1L, 0, 2, 0, 0.0D, 0.0D, 0.0D, List.of(), List.of()),
                "an inactive state is a single page");
    }

    @Test
    void resourcesAndIdentityListsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new SkillStatePacket(UUID.randomUUID(), dimension(),
                true, 1L, 1L, 0, 1, 1, 20.0D, 10.0D, 0.0D, List.of(view(1)), List.of()),
                "MP above the maximum is refused");
        assertThrows(IllegalArgumentException.class, () -> new SkillStatePacket(UUID.randomUUID(), dimension(),
                true, 1L, 1L, 0, 1, 1, Double.NaN, 10.0D, 0.0D, List.of(view(1)), List.of()),
                "a non-finite resource is refused");
        List<Integer> tooManyFriendly = new ArrayList<>();
        for (int index = 0; index < SkillSnapshot.MAX_FRIENDLY_IDS + 1; index++) tooManyFriendly.add(index);
        assertThrows(IllegalArgumentException.class, () -> new SkillStatePacket(UUID.randomUUID(), dimension(),
                true, 1L, 1L, 0, 1, 1, 0.0D, 10.0D, 0.0D, List.of(view(1)), tooManyFriendly),
                "the friendly identity list is bounded");
    }

    @Test
    void aSnapshotIsTheSameValueForTheSameState() {
        SkillSnapshot first = new SkillSnapshot(true, 20.0D, 40.0D, 1.0D, List.of(view(1), view(2)), List.of(12));
        SkillSnapshot same = new SkillSnapshot(true, 20.0D, 40.0D, 1.0D, List.of(view(1), view(2)), List.of(12));
        SkillSnapshot other = new SkillSnapshot(true, 20.0D, 40.0D, 1.0D, List.of(view(1), view(3)), List.of(12));
        assertEquals(first, same, "the server's change detection compares snapshots by value");
        assertNotEquals(first, other);
    }

    private static SkillStatePacket page(int index, int count, int total, List<SkillView> skills) {
        return new SkillStatePacket(UUID.randomUUID(), dimension(), true, 7L, 3L, index, count, total,
                20.0D, 40.0D, 1.0D, skills, List.of());
    }

    private static ResourceLocation dimension() {
        return ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    }

    static SkillView view(int index) {
        return new SkillView(ResourceLocation.fromNamespaceAndPath("tactical", "skill_" + index),
                "技能" + index, "说明" + index, 1, 3, SkillDefinition.Aim.ENTITY, false, false, true, "", 5,
                1.0D, 0);
    }
}
