package com.tactical.neoforge.client;

import com.tactical.neoforge.network.SkillSnapshot;
import com.tactical.neoforge.network.SkillStatePacket;
import com.tactical.neoforge.network.SkillView;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Collects the pages of one complete skill state and hands it over in a single step.
 *
 * <p>A skill state travels as pages because a legitimate full list cannot fit one packet, so the client
 * must not draw a state it only half received. This helper is the whole rule set, kept free of any game
 * or screen class so it can be driven directly by tests:</p>
 *
 * <ul>
 *   <li><b>One authorization context at a time.</b> The pending publication is bound to the full
 *       {@link Key} the caller is allowed to show - encounter, dimension, server epoch and membership
 *       generation - not only to the page it just looked at. A page that does not match the caller's
 *       expected key answers {@link Outcome#MISMATCH} and changes nothing; when the caller's own
 *       authorization context changes, the previous pending batch and its watermarks are dropped, so two
 *       contexts can never contribute one page each to the same list.</li>
 *   <li><b>Neither number may move backwards.</b> Inside one context both the publication number and the
 *       content revision are monotonic: a page of an older publication is {@link Outcome#STALE}, and a
 *       <em>newer</em> publication that carries an older revision is just as stale - a higher publication
 *       never smuggles an older content version back onto the screen.</li>
 *   <li><b>No double submit, no mixing.</b> Every page of one batch must carry the same complete-state
 *       header; a header mismatch drops the batch ({@link Outcome#CONFLICT}). The same page twice is
 *       {@link Outcome#DUPLICATE}; the same page index with different content is a conflict.</li>
 *   <li><b>Nothing is published before it is complete.</b> Only when every page is present does
 *       {@link #accept} return {@link Outcome#COMPLETED}, and the assembled value - total count and unique
 *       skill ids checked against the state header - is taken once with {@link #take()}.</li>
 *   <li><b>The watermark outlives one publication.</b> After a publication completes, its numbers are
 *       retained for the rest of that context (including after an inactive publication), so a delayed page
 *       of an already published snapshot cannot bring an old list back while a role transition is on its
 *       way. Only a real context change or a full reset clears them.</li>
 *   <li><b>Bounded lifetime.</b> {@link #clearExpired} drops a batch that never completed, so a missing
 *       page cannot hold the client in "syncing" forever.</li>
 * </ul>
 */
public final class SkillPageAssembler {

    /** How long one publication may stay incomplete before the caller refetches it. */
    public static final long TIMEOUT_TICKS = 100;

    public enum Outcome { PENDING, COMPLETED, DUPLICATE, STALE, CONFLICT, MISMATCH }

    /** The membership this client currently shows: nothing else may drive its skill state. */
    public record Key(UUID session, ResourceLocation dimension, long serverEpoch, long generation) {
        public Key {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    /** One complete, assembled publication. */
    public record Complete(UUID session, ResourceLocation dimension, long revision, long publication,
                           int pageCount, SkillSnapshot snapshot) { }

    private Key context;
    private long watermarkPublication = -1L;
    private long watermarkRevision = -1L;
    private long publication = -1L;
    private long revision = -1L;
    private long startedAt;
    private int pageCount;
    private int total;
    private SkillStatePacket header;
    private final Map<Integer, SkillStatePacket> pages = new HashMap<>();
    private Complete complete;

    /** Collects one page. Never throws: the caller only acts on {@link Outcome#COMPLETED}. */
    public synchronized Outcome accept(SkillStatePacket page, Key expected, long now) {
        if (page == null || expected == null) return Outcome.MISMATCH;
        if (!expected.equals(keyOf(page))) return Outcome.MISMATCH;
        if (context == null || !context.equals(expected)) {
            // The caller's authorization context really changed: nothing of the old one may leak into it.
            clearBatch();
            context = expected;
            watermarkPublication = -1L;
            watermarkRevision = -1L;
        }
        if (page.revision() < watermarkRevision) return Outcome.STALE;
        // The highest publication this context has already seen: the one being collected, or the last one
        // published. Anything older is a replay.
        long highestPublication = header != null ? Math.max(publication, watermarkPublication) : watermarkPublication;
        if (page.publication() < highestPublication) return Outcome.STALE;
        if (header != null && page.publication() == publication) {
            if (!sameHeader(page)) { clearBatch(); return Outcome.CONFLICT; }
            SkillStatePacket existing = pages.get(page.pageIndex());
            if (existing != null) return existing.equals(page) ? Outcome.DUPLICATE : conflict();
        } else if (page.publication() == watermarkPublication) {
            // That publication is finished: a repeated page is never re-submitted.
            return Outcome.STALE;
        } else {
            start(page, now);
        }
        pages.put(page.pageIndex(), page);
        if (pages.size() < pageCount) return Outcome.PENDING;
        SkillSnapshot assembled = assemble();
        if (assembled == null) return conflict();
        complete = new Complete(context.session(), context.dimension(), revision, publication, pageCount, assembled);
        watermarkPublication = publication;
        watermarkRevision = revision;
        clearBatch();
        return Outcome.COMPLETED;
    }

    /** The assembled publication of the last {@link Outcome#COMPLETED}, once. */
    public synchronized Complete take() {
        Complete value = complete;
        complete = null;
        return value;
    }

    /** Whether a publication is currently incomplete. */
    public synchronized boolean pending() {
        return header != null;
    }

    /** The context this helper currently belongs to; null before the first page and after a reset. */
    public synchronized Key context() {
        return context;
    }

    /** The publication being collected, or -1 when nothing is pending. */
    public synchronized long publication() {
        return publication;
    }

    /** The highest content revision this context has already published, or -1. */
    public synchronized long publishedRevision() {
        return watermarkRevision;
    }

    /** Drops an incomplete batch whose first page is older than {@link #TIMEOUT_TICKS}. */
    public synchronized boolean clearExpired(long now) {
        if (header == null || now - startedAt < TIMEOUT_TICKS) return false;
        clearBatch();
        return true;
    }

    /** Full reset: another session, another world, or a logout. */
    public synchronized void reset() {
        clearBatch();
        complete = null;
        context = null;
        watermarkPublication = -1L;
        watermarkRevision = -1L;
    }

    private void start(SkillStatePacket page, long now) {
        clearBatch();
        publication = page.publication();
        revision = page.revision();
        pageCount = page.pageCount();
        total = page.totalSkillCount();
        header = page;
        startedAt = now;
    }

    private Outcome conflict() {
        clearBatch();
        return Outcome.CONFLICT;
    }

    private void clearBatch() {
        pages.clear();
        header = null;
        pageCount = 0;
        total = 0;
        publication = -1L;
        revision = -1L;
    }

    private static Key keyOf(SkillStatePacket page) {
        return new Key(page.session(), page.dimension(), page.membershipServerEpoch(), page.membershipGeneration());
    }

    /**
     * Every page of one batch carries the same complete-state header and the same authorization context;
     * anything else means two snapshots were interleaved and neither may be published.
     */
    private boolean sameHeader(SkillStatePacket page) {
        return context != null
                && page.session().equals(context.session())
                && page.dimension().equals(context.dimension())
                && page.membershipServerEpoch() == context.serverEpoch()
                && page.membershipGeneration() == context.generation()
                && header.revision() == page.revision()
                && header.active() == page.active()
                && header.pageCount() == page.pageCount()
                && header.totalSkillCount() == page.totalSkillCount()
                && Double.compare(header.mp(), page.mp()) == 0
                && Double.compare(header.maximum(), page.maximum()) == 0
                && Double.compare(header.recovery(), page.recovery()) == 0
                && header.friendlyIds().equals(page.friendlyIds());
    }

    /** The complete state, or {@code null} when the pages do not add up to the announced total. */
    private SkillSnapshot assemble() {
        List<SkillView> skills = new ArrayList<>(total);
        Set<ResourceLocation> unique = new HashSet<>();
        for (int index = 0; index < pageCount; index++) {
            SkillStatePacket page = pages.get(index);
            if (page == null) return null;
            for (SkillView view : page.skills()) {
                if (!unique.add(view.id())) return null;
            }
            skills.addAll(page.skills());
        }
        if (skills.size() != total) return null;
        try {
            // An active state with no skill is legal and is exactly how "the last skill was removed"
            // reaches the client; only an inactive state is a different thing entirely.
            return new SkillSnapshot(header.active(), header.mp(), header.maximum(), header.recovery(), skills,
                    header.friendlyIds());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
