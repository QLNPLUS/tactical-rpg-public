package com.tactical.neoforge.client;

import com.tactical.neoforge.network.SkillStatePacket;
import com.tactical.neoforge.network.SkillView;
import com.tactical.neoforge.skill.SkillDefinition;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client assembles a paged skill state and publishes it in one step.
 *
 * <p>What is locked here is the whole reason the state is paged: a page must never edit the visible list,
 * two publications must never be mixed, an old or duplicated page must never be re-submitted, and a
 * publication that never completes must not hold the client forever. Every outcome is decided by
 * {@link SkillPageAssembler} alone, so no game or screen class is involved.</p>
 */
class SkillPageAssemblerTest {

    private static final UUID SESSION = new UUID(0x1234L, 0x5678L);
    private static final long EPOCH = 4242L;
    private static final long GENERATION = 9L;
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation NETHER = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");
    private static final SkillPageAssembler.Key KEY =
            new SkillPageAssembler.Key(SESSION, OVERWORLD, EPOCH, GENERATION);

    @Test
    void aCompletePublicationIsPublishedExactlyOnce() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        List<SkillStatePacket> pages = pages(2, 6, 100L, 1L);
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(pages.get(0), KEY, 10L));
        assertTrue(assembler.pending());
        assertEquals(SkillPageAssembler.Outcome.COMPLETED, assembler.accept(pages.get(1), KEY, 11L));
        SkillPageAssembler.Complete complete = assembler.take();
        assertNotNull(complete);
        assertEquals(100L, complete.revision());
        assertEquals(1L, complete.publication());
        assertEquals(6, complete.snapshot().skillCount(), "every skill of the whole state arrives");
        assertTrue(complete.snapshot().active());
        assertEquals(List.of(3), complete.snapshot().friendlyIds());
        assertNull(assembler.take(), "one publication is taken once, never twice");
        assertFalse(assembler.pending());
    }

    @Test
    void pagesMayArriveInAnyOrder() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        List<SkillStatePacket> pages = pages(2, 6, 100L, 1L);
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(pages.get(1), KEY, 10L));
        assertEquals(SkillPageAssembler.Outcome.COMPLETED, assembler.accept(pages.get(0), KEY, 10L));
        SkillPageAssembler.Complete complete = assembler.take();
        assertNotNull(complete);
        assertEquals("skill_0", complete.snapshot().skills().get(0).id().getPath(),
                "rows keep the page order, not the arrival order");
    }

    @Test
    void aMissingPageNeverPublishesAnything() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        List<SkillStatePacket> pages = pages(2, 6, 100L, 1L);
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(pages.get(0), KEY, 10L));
        assertNull(assembler.take());
        assertTrue(assembler.clearExpired(10L + SkillPageAssembler.TIMEOUT_TICKS - 1) == false,
                "a publication is not abandoned before its timeout");
        assertTrue(assembler.clearExpired(10L + SkillPageAssembler.TIMEOUT_TICKS), "an incomplete one is dropped");
        assertNull(assembler.take());
        assertFalse(assembler.pending());
    }

    @Test
    void theSamePageTwiceIsNotSubmittedTwice() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        List<SkillStatePacket> pages = pages(2, 6, 100L, 1L);
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(pages.get(0), KEY, 10L));
        assertEquals(SkillPageAssembler.Outcome.DUPLICATE, assembler.accept(pages.get(0), KEY, 11L));
        assertEquals(SkillPageAssembler.Outcome.COMPLETED, assembler.accept(pages.get(1), KEY, 12L));
        assertNotNull(assembler.take(), "a duplicate neither blocks nor double-counts the publication");
    }

    @Test
    void theSamePageIndexWithOtherContentIsAConflict() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        List<SkillStatePacket> pages = pages(2, 6, 100L, 1L);
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(pages.get(0), KEY, 10L));
        SkillStatePacket different = page(0, 2, 6, 100L, 1L, 0, 99, List.of(view(99)));
        assertEquals(SkillPageAssembler.Outcome.CONFLICT, assembler.accept(different, KEY, 11L));
        assertNull(assembler.take(), "a conflicted batch is dropped, never published");
        assertFalse(assembler.pending());
    }

    @Test
    void aPageOfAnotherSnapshotCannotJoinTheBatch() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        List<SkillStatePacket> pages = pages(2, 6, 100L, 1L);
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(pages.get(0), KEY, 10L));
        SkillStatePacket otherRevision = page(1, 2, 6, 101L, 1L, 3, 6, skills(3, 6));
        assertEquals(SkillPageAssembler.Outcome.CONFLICT, assembler.accept(otherRevision, KEY, 11L));
        assertNull(assembler.take());
    }

    @Test
    void anOldPublicationOrAnAlreadyPublishedOneIsStale() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        List<SkillStatePacket> pages = pages(2, 6, 100L, 2L);
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(pages.get(0), KEY, 10L));
        assertEquals(SkillPageAssembler.Outcome.COMPLETED, assembler.accept(pages.get(1), KEY, 10L));
        assembler.take();
        assertEquals(SkillPageAssembler.Outcome.STALE, assembler.accept(pages.get(0), KEY, 11L),
                "a page of a publication that already completed is never re-submitted");
        assertEquals(SkillPageAssembler.Outcome.STALE, assembler.accept(page(0, 1, 1, 99L, 1L, 0, 1, List.of(view(0))), KEY, 12L),
                "a page of an older publication is dropped");
    }

    @Test
    void aNewerPublicationStartsOverInsteadOfMixing() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        List<SkillStatePacket> first = pages(2, 6, 100L, 1L);
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(first.get(0), KEY, 10L));
        List<SkillStatePacket> second = pages(1, 2, 100L, 2L);
        assertEquals(SkillPageAssembler.Outcome.COMPLETED, assembler.accept(second.get(0), KEY, 11L),
                "the newer forced publication replaces the unfinished one entirely");
        SkillPageAssembler.Complete complete = assembler.take();
        assertNotNull(complete);
        assertEquals(2L, complete.publication());
        assertEquals(2, complete.snapshot().skillCount());
        assertEquals(100L, complete.revision(), "a forced resend may repeat the revision");
    }

    @Test
    void anotherMembershipOrDimensionNeverTouchesThePendingBatch() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        List<SkillStatePacket> pages = pages(2, 6, 100L, 1L);
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(pages.get(0), KEY, 10L));
        SkillPageAssembler.Key otherEpoch = new SkillPageAssembler.Key(SESSION, OVERWORLD, EPOCH + 1, GENERATION);
        SkillPageAssembler.Key otherSession = new SkillPageAssembler.Key(UUID.randomUUID(), OVERWORLD, EPOCH, GENERATION);
        SkillPageAssembler.Key otherWorld = new SkillPageAssembler.Key(SESSION, NETHER, EPOCH, GENERATION);
        assertEquals(SkillPageAssembler.Outcome.MISMATCH, assembler.accept(pages.get(1), otherEpoch, 11L));
        assertEquals(SkillPageAssembler.Outcome.MISMATCH, assembler.accept(pages.get(1), otherSession, 11L));
        assertEquals(SkillPageAssembler.Outcome.MISMATCH, assembler.accept(pages.get(1), otherWorld, 11L));
        assertNull(assembler.take(), "a stale membership cannot complete this publication");
        assertEquals(SkillPageAssembler.Outcome.COMPLETED, assembler.accept(pages.get(1), KEY, 12L),
                "and the real membership still completes it");
    }

    @Test
    void pagesThatDoNotAddUpAreRefused() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        // The header announces 6 skills but the two pages only carry 5: no value may be published.
        SkillStatePacket shortPage = page(1, 2, 6, 100L, 1L, 3, 5, skills(3, 5));
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(page(0, 2, 6, 100L, 1L, 0, 3, skills(0, 3)), KEY, 10L));
        assertEquals(SkillPageAssembler.Outcome.CONFLICT, assembler.accept(shortPage, KEY, 11L));
        assertNull(assembler.take());
    }

    @Test
    void aRepeatedSkillAcrossPagesIsRefused() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(page(0, 2, 2, 100L, 1L, 0, 1, List.of(view(0))), KEY, 10L));
        assertEquals(SkillPageAssembler.Outcome.CONFLICT,
                assembler.accept(page(1, 2, 2, 100L, 1L, 1, 1, List.of(view(0))), KEY, 11L),
                "the same skill on two pages is an inconsistent state, not a list");
        assertNull(assembler.take());
    }

    @Test
    void anInactiveStateIsASingleEmptyPublication() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        SkillStatePacket inactive = new SkillStatePacket(SESSION, OVERWORLD, false, 5L, 1L, 0, 1, 0,
                0.0D, 0.0D, 0.0D, List.of(), List.of()).withMembership(EPOCH, GENERATION);
        assertEquals(SkillPageAssembler.Outcome.COMPLETED, assembler.accept(inactive, KEY, 10L));
        SkillPageAssembler.Complete complete = assembler.take();
        assertNotNull(complete);
        assertFalse(complete.snapshot().active());
        assertEquals(0, complete.snapshot().skillCount());
    }

    @Test
    void aResetForgetsEverythingIncludingThePublishedPublication() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        List<SkillStatePacket> pages = pages(2, 6, 100L, 4L);
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(pages.get(0), KEY, 10L));
        assembler.reset();
        assertFalse(assembler.pending());
        assertNull(assembler.take());
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(pages.get(0), KEY, 11L),
                "after a full reset the same publication is collected again from its first page");
        assertEquals(SkillPageAssembler.Outcome.COMPLETED, assembler.accept(pages.get(1), KEY, 11L));
        assertNotNull(assembler.take());
    }

    // ---- R2: one context, monotonic numbers ---------------------------------------------

    @Test
    void onePageOfAnotherContextCannotJoinThisOne() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        // The same encounter id and dimension, but another membership stamp: a legally different context.
        SkillPageAssembler.Key other = new SkillPageAssembler.Key(SESSION, OVERWORLD, EPOCH, GENERATION + 1);
        SkillStatePacket firstOfThis = pageForKey(KEY, 0, 2, 6, 100L, 1L, 0, 3);
        SkillStatePacket secondOfOther = pageForKey(other, 1, 2, 6, 100L, 1L, 3, 6);
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(firstOfThis, KEY, 10L));
        assertEquals(SkillPageAssembler.Outcome.PENDING, assembler.accept(secondOfOther, other, 11L),
                "the other context starts its own batch instead of completing this one");
        assertNull(assembler.take(), "two contexts never make one list, even with identical numbers");
        assertEquals(SkillPageAssembler.Outcome.COMPLETED,
                assembler.accept(pageForKey(other, 0, 2, 6, 100L, 1L, 0, 3), other, 12L));
        SkillPageAssembler.Complete complete = assembler.take();
        assertNotNull(complete);
        assertEquals(other, assembler.context(), "the helper now belongs to the newer context");
        assertEquals(6, complete.snapshot().skillCount());
    }

    @Test
    void aPageOfAnotherContextIsStillMismatchedForTheExpectedOne() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        SkillPageAssembler.Key other = new SkillPageAssembler.Key(SESSION, OVERWORLD, EPOCH + 1, GENERATION);
        assertEquals(SkillPageAssembler.Outcome.MISMATCH,
                assembler.accept(pageForKey(other, 0, 1, 1, 100L, 1L, 0, 1), KEY, 10L),
                "the page must belong to the caller's own authorized context");
        assertFalse(assembler.pending());
        assertNull(assembler.take());
        assertNull(assembler.context(), "a mismatched page never becomes this helper's context");
    }

    @Test
    void aNewerPublicationCannotBringBackAnOlderRevision() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        assertEquals(SkillPageAssembler.Outcome.COMPLETED,
                assembler.accept(pageForKey(KEY, 0, 1, 1, 8L, 5L, 0, 1), KEY, 10L));
        assertNotNull(assembler.take());
        assertEquals(8L, assembler.publishedRevision());
        assertEquals(SkillPageAssembler.Outcome.STALE, assembler.accept(pageForKey(KEY, 0, 1, 1, 7L, 99L, 0, 1), KEY, 11L),
                "a higher publication number never smuggles an older content revision onto the screen");
        assertNull(assembler.take());
        assertEquals(SkillPageAssembler.Outcome.COMPLETED, assembler.accept(pageForKey(KEY, 0, 1, 1, 8L, 100L, 0, 1), KEY, 12L),
                "a forced republish of the same revision under a new publication is accepted");
        assertEquals(100L, assembler.take().publication());
        assertEquals(SkillPageAssembler.Outcome.COMPLETED, assembler.accept(pageForKey(KEY, 0, 1, 1, 9L, 101L, 0, 1), KEY, 13L),
                "a real content change keeps moving forward");
    }

    @Test
    void aDelayedPageOfAnAlreadyPublishedSnapshotIsStale() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        assertEquals(SkillPageAssembler.Outcome.COMPLETED,
                assembler.accept(pageForKey(KEY, 0, 1, 1, 8L, 5L, 0, 1), KEY, 10L));
        assembler.take();
        assertEquals(SkillPageAssembler.Outcome.STALE, assembler.accept(pageForKey(KEY, 0, 1, 1, 8L, 5L, 0, 1), KEY, 11L),
                "the same snapshot is never published twice");
    }

    // ---- R1: a legitimate empty active state --------------------------------------------

    @Test
    void anActiveStateWithoutSkillsIsPublishedAsAnEmptyList() {
        SkillPageAssembler assembler = new SkillPageAssembler();
        SkillStatePacket empty = new SkillStatePacket(SESSION, OVERWORLD, true, 9L, 4L, 0, 1, 0,
                40.0D, 60.0D, 1.5D, List.of(), List.of(3)).withMembership(EPOCH, GENERATION);
        assertEquals(SkillPageAssembler.Outcome.COMPLETED, assembler.accept(empty, KEY, 10L));
        SkillPageAssembler.Complete complete = assembler.take();
        assertNotNull(complete, "the empty active state is a complete publication of its own");
        assertTrue(complete.snapshot().active(), "it is not the inactive contract");
        assertEquals(0, complete.snapshot().skillCount(), "the client is told the list is empty");
        assertEquals(40.0D, complete.snapshot().mp(), "MP still arrives with the empty list");
        assertEquals(List.of(3), complete.snapshot().friendlyIds());
        assertEquals(1, complete.pageCount());
    }

    // ---- fixtures ----------------------------------------------------------------------

    private static List<SkillStatePacket> pages(int pageCount, int total, long revision, long publication) {
        List<SkillStatePacket> pages = new ArrayList<>(pageCount);
        int perPage = (total + pageCount - 1) / pageCount;
        for (int index = 0; index < pageCount; index++) {
            int from = index * perPage;
            int to = Math.min(total, from + perPage);
            pages.add(page(index, pageCount, total, revision, publication, from, to, skills(from, to)));
        }
        return pages;
    }

    private static SkillStatePacket pageForKey(SkillPageAssembler.Key key, int index, int pageCount, int total,
                                              long revision, long publication, int from, int to) {
        List<SkillView> views = new ArrayList<>();
        for (int skill = from; skill < to; skill++) views.add(view(skill));
        return new SkillStatePacket(key.session(), key.dimension(), true, revision, publication, index, pageCount,
                total, 30.0D, 60.0D, 1.0D, views, List.of(3))
                .withMembership(key.serverEpoch(), key.generation());
    }

    private static SkillStatePacket page(int index, int pageCount, int total, long revision, long publication,
                                         int from, int to, List<SkillView> skills) {
        // The server stamps each page for its recipient on the way out; the client only accepts its own.
        return new SkillStatePacket(SESSION, OVERWORLD, true, revision, publication, index, pageCount, total,
                30.0D, 60.0D, 1.0D, skills, List.of(3)).withMembership(EPOCH, GENERATION);
    }

    private static List<SkillView> skills(int from, int to) {
        List<SkillView> views = new ArrayList<>();
        for (int index = from; index < to; index++) views.add(view(index));
        return views;
    }

    private static SkillView view(int index) {
        return new SkillView(ResourceLocation.fromNamespaceAndPath("tactical", "skill_" + index),
                "技能" + index, "说明" + index, 1, 3, SkillDefinition.Aim.ENTITY, false, false, true, "", 5,
                1.0D, 0);
    }
}
