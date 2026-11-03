package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.skill.SkillDefinition;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One page of the client's skill state.
 *
 * <p>A skill list is not one packet. A legitimate state can carry 256 skills, each with three component
 * frames of up to {@link ComponentIO#MAX_FRAME_BYTES} wire bytes, so a single frame would either break the
 * transport ceiling (a clientbound custom payload is capped at 1 MiB) or force the list to shrink. The
 * complete state therefore travels as pages that are assembled on the client and published atomically;
 * no skill count and no description is reduced to make it fit.</p>
 *
 * <p>Every page repeats the same complete-state header - session, dimension, active flag, state revision,
 * publication number, page count, total skill count, resources and the friendly identity list - so a page
 * from another snapshot can never be mixed into this one, and a missing page is detectable. The
 * publication number is separate from the revision on purpose: a forced resend republishes the same
 * content under a new publication.</p>
 *
 * <p>Wire layout (the packet body, framed by the game's own packet framing):</p>
 * <pre>
 *   VarInt  bodyLength          // bytes the rest of this layout occupies; bounded by MAX_BODY_BYTES
 *   UUID    session
 *   ResourceLocation dimension
 *   boolean active
 *   long    revision
 *   long    publication
 *   VarInt  pageIndex           // 0..pageCount-1
 *   VarInt  pageCount           // 1..MAX_PAGES
 *   VarInt  totalSkillCount     // 0..MAX_SKILLS, the whole state, not this page
 *   double  mp, maximum, recovery
 *   VarInt  skillCount          // 1..MAX_PER_PAGE when active, 0 when inactive
 *   repeat skillCount times:
 *     ResourceLocation id
 *     ComponentIO frame          name
 *     ComponentIO frame          description
 *     VarInt level, VarInt maxLevel, VarInt aim
 *     boolean area, boolean targetCosts, boolean ready
 *     ComponentIO frame          reason
 *     VarInt apCost, double mpCost, VarInt cooldown
 *   VarInt  friendlyCount (0..4096) + VarInt each
 *   VarLong membershipServerEpoch
 *   VarLong membershipGeneration
 * </pre>
 *
 * <p>The declared body length is checked against {@link #MAX_BODY_BYTES} before anything is allocated or
 * decoded, every field counts against it, and every component frame carries its own limit from
 * {@link ComponentIO}. Encoding builds the body in a scratch buffer capped at the same budget, so an
 * over-sized page is refused instead of being half written to the connection.</p>
 */
public record SkillStatePacket(UUID session, ResourceLocation dimension, boolean active, long revision,
                               long publication, int pageIndex, int pageCount, int totalSkillCount,
                               double mp, double maximum, double recovery, List<SkillView> skills,
                               List<Integer> friendlyIds, long membershipServerEpoch, long membershipGeneration)
        implements CustomPacketPayload, MemberScoped {

    public static final int MAX_BODY_BYTES = 900 * 1024;

    /** Unstamped page: the network layer stamps each recipient's own membership. */
    public SkillStatePacket(UUID session, ResourceLocation dimension, boolean active, long revision,
                            long publication, int pageIndex, int pageCount, int totalSkillCount, double mp,
                            double maximum, double recovery, List<SkillView> skills, List<Integer> friendlyIds) {
        this(session, dimension, active, revision, publication, pageIndex, pageCount, totalSkillCount, mp, maximum,
                recovery, skills, friendlyIds, 0L, 0L);
    }

    @Override
    public UUID membershipSession() {
        return session;
    }

    @Override
    public SkillStatePacket withMembership(long serverEpoch, long generation) {
        if (serverEpoch == membershipServerEpoch && generation == membershipGeneration) return this;
        return new SkillStatePacket(session, dimension, active, revision, publication, pageIndex, pageCount,
                totalSkillCount, mp, maximum, recovery, skills, friendlyIds, serverEpoch, generation);
    }

    @Override
    public long membershipServerEpoch() {
        return membershipServerEpoch;
    }

    @Override
    public long membershipGeneration() {
        return membershipGeneration;
    }

    public static final Type<SkillStatePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"skill_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkillStatePacket> STREAM_CODEC =
            StreamCodec.of(SkillStatePacket::encode, SkillStatePacket::decode);

    public SkillStatePacket {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(dimension, "dimension");
        skills = List.copyOf(skills);
        friendlyIds = List.copyOf(friendlyIds);
        SkillPacketIO.finiteResource(mp);
        SkillPacketIO.finiteResource(maximum);
        SkillPacketIO.finiteResource(recovery);
        if (mp > maximum || publication < 0 || revision < 0) throw new IllegalArgumentException("Invalid skill state");
        if (pageCount < 1 || pageCount > SkillPacketIO.MAX_PAGES || pageIndex < 0 || pageIndex >= pageCount)
            throw new IllegalArgumentException("Invalid skill state page index");
        if (totalSkillCount < 0 || totalSkillCount > SkillPacketIO.MAX_SKILLS || friendlyIds.size() > SkillSnapshot.MAX_FRIENDLY_IDS)
            throw new IllegalArgumentException("Invalid skill state count");
        if (!active) {
            // The existing inactive contract: one page that carries no skills at all.
            if (!skills.isEmpty() || totalSkillCount != 0 || pageCount != 1)
                throw new IllegalArgumentException("Inactive skill state carries no skills");
        } else {
            // An active state with no skills is a legitimate state of its own: a member who owns no skill
            // still has MP, recovery and allied identity to receive, and removing the last skill has to
            // reach the client as an empty list instead of leaving the old one on screen.
            int minimum = SkillPacketIO.minimumPages(true, totalSkillCount);
            if (pageCount < minimum || pageCount > Math.max(1, totalSkillCount))
                throw new IllegalArgumentException("Skill state page count does not fit the total");
            if (skills.size() > SkillPacketIO.MAX_PER_PAGE || totalSkillCount < skills.size())
                throw new IllegalArgumentException("Invalid skill state page size");
            if (totalSkillCount == 0) {
                if (!skills.isEmpty() || pageCount != 1)
                    throw new IllegalArgumentException("An active state without skills is a single empty page");
            } else if (skills.isEmpty()) {
                throw new IllegalArgumentException("A page of a non-empty skill state carries skills");
            }
            LinkedHashSet<ResourceLocation> unique = new LinkedHashSet<>();
            for (SkillView view : skills) {
                if (view == null || !unique.add(view.id()))
                    throw new IllegalArgumentException("Duplicate skill on one page");
            }
        }
    }

    /** The complete state this page belongs to, as the client publishes it once all pages arrived. */
    public SkillSnapshot snapshot() {
        return new SkillSnapshot(active, mp, maximum, recovery, skills, friendlyIds);
    }

    /** Whether {@code other} belongs to the same complete publication (same header, same page). */
    public boolean samePageAs(SkillStatePacket other) {
        return other != null && pageIndex == other.pageIndex && equals(other);
    }

    /**
     * Slice one complete state into pages.
     *
     * <p>Grouping respects the encoded size, not only the skill count: a page is closed when adding one
     * more skill would push its real body past {@link #MAX_BODY_BYTES}. With the measured worst case
     * ({@link SkillPacketIO#MAX_PER_PAGE} skills of three maximal frames) that ceiling is never reached, so
     * a legitimate full list still occupies
     * {@link SkillPacketIO#minimumPages(boolean, int)} pages - but the guard is the encoder, not an
     * assumption about the fields.</p>
     */
    public static List<SkillStatePacket> pack(UUID session, ResourceLocation dimension, SkillSnapshot snapshot,
                                             long revision, long publication, RegistryAccess registries) {
        Objects.requireNonNull(snapshot, "snapshot");
        // Inactive and "active but no skill granted" both travel as one page that carries the shared state
        // only - MP, maximum, recovery and the friendly identity list are never withheld from a member.
        if (!snapshot.active() || snapshot.skills().isEmpty()) {
            return List.of(new SkillStatePacket(session, dimension, snapshot.active(), revision, publication, 0, 1, 0,
                    snapshot.mp(), snapshot.maximum(), snapshot.recovery(), List.of(), snapshot.friendlyIds()));
        }
        List<List<SkillView>> groups = new ArrayList<>();
        List<SkillView> current = new ArrayList<>();
        for (SkillView view : snapshot.skills()) {
            current.add(view);
            boolean tooMany = current.size() > SkillPacketIO.MAX_PER_PAGE;
            if (tooMany || !fits(session, dimension, snapshot, revision, publication, current, registries)) {
                current.removeLast();
                if (current.isEmpty())
                    throw new IllegalArgumentException("One skill alone does not fit a skill state page");
                groups.add(List.copyOf(current));
                if (groups.size() >= SkillPacketIO.MAX_PAGES)
                    throw new IllegalArgumentException("Skill state needs more pages than the protocol allows");
                current = new ArrayList<>();
                current.add(view);
                if (!fits(session, dimension, snapshot, revision, publication, current, registries))
                    throw new IllegalArgumentException("One skill alone does not fit a skill state page");
            }
        }
        if (!current.isEmpty()) groups.add(List.copyOf(current));
        if (groups.size() > SkillPacketIO.MAX_PAGES)
            throw new IllegalArgumentException("Skill state needs more pages than the protocol allows");
        int total = snapshot.skills().size();
        List<SkillStatePacket> pages = new ArrayList<>(groups.size());
        for (int index = 0; index < groups.size(); index++) {
            pages.add(new SkillStatePacket(session, dimension, true, revision, publication, index, groups.size(),
                    total, snapshot.mp(), snapshot.maximum(), snapshot.recovery(), groups.get(index),
                    snapshot.friendlyIds()));
        }
        return List.copyOf(pages);
    }

    /**
     * Whether one candidate page really encodes inside the body budget; the encoder is the judge.
     *
     * <p>The probe carries the smallest legal header for this state ({@code pageIndex 0}, the minimum page
     * count), because only the content is being measured - the header is a fixed handful of bytes.</p>
     */
    private static boolean fits(UUID session, ResourceLocation dimension, SkillSnapshot snapshot, long revision,
                                long publication, List<SkillView> skills, RegistryAccess registries) {
        if (skills.isEmpty()) return true;
        int total = snapshot.skills().size();
        try {
            measure(new SkillStatePacket(session, dimension, true, revision, publication, 0,
                    SkillPacketIO.minimumPages(true, total), total, snapshot.mp(), snapshot.maximum(),
                    snapshot.recovery(), List.copyOf(skills), snapshot.friendlyIds()), registries);
            return true;
        } catch (IllegalArgumentException refused) {
            return false;
        }
    }

    /** Encodes one page body and returns its size, refusing anything above the body budget. */
    private static int measure(SkillStatePacket page, RegistryAccess registries) {
        ByteBuf body = Unpooled.buffer(256, MAX_BODY_BYTES);
        try {
            writeBody(new RegistryFriendlyByteBuf(body, registries == null
                    ? RegistryAccess.EMPTY : registries, net.neoforged.neoforge.network.connection.ConnectionType.OTHER), page);
            return body.readableBytes();
        } finally {
            body.release();
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, SkillStatePacket packet) {
        if (buffer == null) throw new IllegalArgumentException("Missing skill buffer");
        if (packet == null) throw new IllegalArgumentException("Missing skill state page");
        ByteBuf body = Unpooled.buffer(256, MAX_BODY_BYTES);
        try {
            try {
                writeBody(new RegistryFriendlyByteBuf(body, buffer.registryAccess(), buffer.getConnectionType()), packet);
            } catch (RuntimeException failure) {
                // A component frame refused its own limit or the page outgrew the body budget: nothing of
                // this page may reach the connection.
                throw new IllegalArgumentException("Skill state page does not fit the " + MAX_BODY_BYTES
                        + "-byte page budget", failure);
            }
            int size = body.readableBytes();
            int written = VarInt.getByteSize(size) + size;
            if (buffer.maxWritableBytes() < written) {
                throw new IllegalArgumentException("Skill state page of " + written
                        + " bytes does not fit the remaining capacity of the buffer");
            }
            int start = buffer.writerIndex();
            try {
                buffer.writeVarInt(size);
                buffer.writeBytes(body, body.readerIndex(), size);
            } catch (RuntimeException failure) {
                buffer.writerIndex(start);
                throw new IllegalArgumentException("Skill state page could not be written to the buffer", failure);
            }
        } finally {
            body.release();
        }
    }

    private static void writeBody(RegistryFriendlyByteBuf body, SkillStatePacket page) {
        body.writeUUID(page.session);
        body.writeResourceLocation(page.dimension);
        body.writeBoolean(page.active);
        body.writeLong(page.revision);
        body.writeLong(page.publication);
        body.writeVarInt(page.pageIndex);
        body.writeVarInt(page.pageCount);
        body.writeVarInt(page.totalSkillCount);
        body.writeDouble(page.mp);
        body.writeDouble(page.maximum);
        body.writeDouble(page.recovery);
        body.writeVarInt(page.skills.size());
        for (SkillView view : page.skills) {
            body.writeResourceLocation(view.id());
            ComponentIO.write(body, view.name());
            ComponentIO.write(body, view.description());
            body.writeVarInt(view.level());
            body.writeVarInt(view.maxLevel());
            body.writeVarInt(view.aim().ordinal());
            body.writeBoolean(view.area());
            body.writeBoolean(view.targetCosts());
            body.writeBoolean(view.ready());
            ComponentIO.write(body, view.reason());
            body.writeVarInt(view.apCost());
            body.writeDouble(view.mpCost());
            body.writeVarInt(view.cooldown());
        }
        body.writeVarInt(page.friendlyIds.size());
        for (int id : page.friendlyIds) body.writeVarInt(id);
        body.writeVarLong(page.membershipServerEpoch);
        body.writeVarLong(page.membershipGeneration);
    }

    private static SkillStatePacket decode(RegistryFriendlyByteBuf buffer) {
        if (buffer == null) throw new IllegalArgumentException("Missing skill buffer");
        int length;
        try {
            length = buffer.readVarInt();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Skill state page length is unreadable", failure);
        }
        // Both bounds are checked before a slice is taken: a hostile declaration can neither allocate nor
        // be trusted, and a page never reads into the next one.
        if (length < 0) throw new IllegalArgumentException("Negative skill state page length");
        if (length > MAX_BODY_BYTES) throw new IllegalArgumentException("Skill state page exceeds the body budget");
        int available = buffer.readableBytes();
        if (length > available) throw new IllegalArgumentException("Skill state page declares more bytes than remain");
        RegistryFriendlyByteBuf body = new RegistryFriendlyByteBuf(buffer.readSlice(length),
                buffer.registryAccess(), buffer.getConnectionType());
        UUID session = body.readUUID();
        ResourceLocation dimension = body.readResourceLocation();
        boolean active = body.readBoolean();
        long revision = body.readLong();
        long publication = body.readLong();
        int pageIndex = body.readVarInt();
        int pageCount = body.readVarInt();
        int total = body.readVarInt();
        double mp = body.readDouble(), maximum = body.readDouble(), recovery = body.readDouble();
        int count = SkillPacketIO.size(body, SkillPacketIO.MAX_PER_PAGE);
        List<SkillView> skills = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation id = body.readResourceLocation();
            net.minecraft.network.chat.Component name = ComponentIO.read(body);
            net.minecraft.network.chat.Component description = ComponentIO.read(body);
            int level = body.readVarInt(), maxLevel = body.readVarInt();
            int aim = body.readVarInt();
            if (aim < 0 || aim >= SkillDefinition.Aim.values().length)
                throw new IllegalArgumentException("Invalid skill aim");
            boolean area = body.readBoolean(), targetCosts = body.readBoolean(), ready = body.readBoolean();
            net.minecraft.network.chat.Component reason = ComponentIO.read(body);
            int apCost = body.readVarInt();
            double mpCost = body.readDouble();
            int cooldown = body.readVarInt();
            skills.add(new SkillView(id, name, description, level, maxLevel, SkillDefinition.Aim.values()[aim], area,
                    targetCosts, ready, reason, apCost, mpCost, cooldown));
        }
        int friendlyCount = SkillPacketIO.size(body, SkillSnapshot.MAX_FRIENDLY_IDS);
        List<Integer> friendly = new ArrayList<>(friendlyCount);
        for (int i = 0; i < friendlyCount; i++) friendly.add(body.readVarInt());
        long epoch = body.readVarLong(), generation = body.readVarLong();
        int trailing = body.readableBytes();
        if (trailing != 0) throw new IllegalArgumentException("Skill state page carries " + trailing + " trailing bytes");
        return new SkillStatePacket(session, dimension, active, revision, publication, pageIndex, pageCount, total,
                mp, maximum, recovery, skills, friendly, epoch, generation);
    }

    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
