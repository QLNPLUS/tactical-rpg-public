package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.battle.multi.ActionStamp;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import com.tactical.neoforge.network.BattleStatePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Disposable dedicated-server fixture for the two block commands and the forced fall:
 * {@code -Dtactical.selftest=block_terrain}.
 *
 * <p>Every stage runs against a real {@link ServerLevel} and the real vanilla entry points, so it can
 * tell "the server answered yes" from "the world really changed". The checks are grouped by the promise
 * they prove: the native chains are really used (durability and loot come from vanilla), a refusal
 * changes nothing, a lost support drops the unit and settles the landing through the vanilla fall
 * calculation, and no fall state outlives its deadline.</p>
 *
 * <p>It never launches a client and never touches the user's world: the run directory is its own (see
 * the {@code serverBlockTerrainTest} run in {@code build.gradle}). A failure exits the server non-zero -
 * a green Gradle task must never look like a passing fixture.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BlockTerrainSelfTest {

    private static boolean enabled;
    /** A test-only protection: while this position is set, its break event is cancelled. */
    private static BlockPos protectedPos;
    private static int ticks;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static FakePlayer partner;
    private static Zombie bystander;
    private static ServerBattle battle;
    private static Wire wire;
    private static int checks;
    /** The configured zone height this fixture changed, restored when it finishes. */
    private static int defaultZoneHeight = -1;
    private static final List<Entity> spawned = new ArrayList<>();
    private static final List<BlockPos> forced = new ArrayList<>();

    private BlockTerrainSelfTest() {
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        enabled = "block_terrain".equals(System.getProperty("tactical.selftest"));
        ticks = 0;
        checks = 0;
    }

    /**
     * Test-only protection listener.
     *
     * <p>It is the very event a protection mod cancels, so the refusal path is exercised inside the
     * native break chain instead of being simulated: the block must survive and the attempt must cost
     * nothing.</p>
     */
    @SubscribeEvent
    public static void protect(BlockEvent.BreakEvent event) {
        if (enabled && protectedPos != null && protectedPos.equals(event.getPos())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!enabled) return;
        try {
            if (++ticks == 20) {
                setup(event.getServer());
                return;
            }
            if (ticks < 40) return;
            check(ticks < 2400, "fixture deadline");
            if (bystander == null || !level.getEntitiesOfClass(Zombie.class, owner.getBoundingBox().inflate(12))
                    .contains(bystander)) return;
            run();
            Tactical.LOGGER.info("[Tactical] BLOCK_TERRAIN SELFTEST PASSED: {} checks on the native placement/"
                    + "break chain, its refusals, and the forced fall with vanilla landing settlement", checks);
            finish(event.getServer());
        } catch (Exception | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] BLOCK_TERRAIN SELFTEST FAILED after {} checks", checks, failure);
            finish(event.getServer());
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw new IllegalStateException(failure);
        }
    }

    // ------------------------------------------------------------------ stages

    private static void run() throws Exception {
        check(BattleManager.start(owner, true), "native battle starts");
        battle = BattleManager.current();
        check(battle != null && battle.isPlayerTurnNow(), "the owner acts first");
        check(battle.currentBreakCost() == 10, "the break quote is its own base price");
        check(battle.quotePlaceCost() == 10 && battle.quoteItemCost() == 8,
                "breaking did not change the other two prices");

        placementAndTheItemEntry();
        partialBlockHit();
        breaking();
        refusals();
        forcedFall();
        noLandingRefusal();
        flyingUnitsAreNotAffected();
        blockedMidStep();
        subCellPushFall();
        subCellPushOverAPit();
        compoundTreadUnderTheFootprint();
        liveLevitationIsNotDropped();
        groundRecipientThroughTheBridge();
        fallDeath();
    }

    /**
     * An accepted push that ended <em>inside</em> one cell: the fall has to start from the real feet.
     *
     * <p>This is the observed D4 first difference, proved on a live server. The push leaves the body 0.601 of a
     * block above the only floor under it while its integer cell still has a solid block beneath it, so the
     * ordinary "is there a block below the cell" question answers "supported" about a body standing in mid air.
     * The hand-back asks the real whole body instead: a fall must begin on the spot, descend in bounded slices
     * without ever leaving the lateral position the push gave it, land <em>on</em> the real surface rather than a
     * fraction above it, and cost neither AP, an action, nor any other member's resources.</p>
     */
    private static void subCellPushFall() {
        ensureBreakAffordable();
        settle();
        GridPos cell = battle.playerCell();
        check(cell != null, "the acting member has an authoritative cell");
        flatCell(cell);
        owner.setPos(cell.x() + .5D, cell.y(), cell.z() + .5D);
        owner.setDeltaMovement(Vec3.ZERO);
        owner.setHealth(owner.getMaxHealth());
        owner.removeEffect(MobEffects.LEVITATION);
        int ap = battle.currentAp();
        int paid = battle.successfulPaidActions();
        int partnerPaid = battle.fixtureMemberPaid(partner);
        float health = owner.getHealth();
        float partnerHealth = partner.getHealth();
        check(!battle.terrainFallActive(), "the sub-cell stage starts with no fall in flight");
        // The push vanilla accepted in the observed scene: a lateral part that stays inside the cell and a
        // vertical part that leaves the body less than one block above its own floor.
        Vec3 push = new Vec3(-0.293D, 0.601D, 0.462D);
        check(battle.fixtureAdoptPush(owner, push), "the accepted push really moved the owner");
        double exactX = cell.x() + 0.207D;
        double exactZ = cell.z() + 0.962D;
        check(close(owner.getY(), cell.y() + 0.601D), "the body is at the fractional height: " + owner.getY());
        check(close(owner.getX(), exactX) && close(owner.getZ(), exactZ),
                "the push's real lateral feet are authoritative: " + owner.getX() + "," + owner.getZ());
        check(battle.terrainFallActive(),
                "the unsupported fractional feet start a fall in the same tick instead of idling there");
        check(owner.isAlive() && owner.getHealth() == health, "the push itself changed no health");
        check(battle.currentAp() == ap && battle.successfulPaidActions() == paid,
                "the accepted push charged no AP and counted as no action");
        check(battle.fixtureMemberPaid(partner) == partnerPaid && partner.getHealth() == partnerHealth,
                "the other member paid nothing for this push");

        List<Double> heights = new ArrayList<>();
        for (int guard = 0; guard < 40 && battle.terrainFallActive(); guard++) {
            battle.tick();
            heights.add(owner.getY());
            check(close(owner.getX(), exactX) && close(owner.getZ(), exactZ),
                    "every slice keeps the real lateral position: " + owner.getX() + "," + owner.getZ());
        }
        check(!battle.terrainFallActive(), "the fractional descent settles inside the encounter");
        check(heights.size() >= 2, "the descent is bounded slices, not one snap: " + heights);
        for (int index = 1; index < heights.size(); index++) {
            check(heights.get(index) < heights.get(index - 1),
                    "the descent is monotonic: " + heights.get(index - 1) + " -> " + heights.get(index));
        }
        check(close(owner.getY(), cell.y()),
                "it lands on the real support surface, not a fraction above it: " + owner.getY());
        check(battle.playerCell().equals(cell),
                "a drop inside one cell keeps the cell: " + battle.playerCell() + " vs " + cell);
        check(owner.getHealth() == health, "a sub-block drop inside one cell costs no fall damage");
        check(battle.currentAp() == ap && battle.successfulPaidActions() == paid
                        && battle.fixtureMemberPaid(partner) == partnerPaid,
                "the fall charged nothing at all");

        // Stable later reconciliation: the exact feet the fall landed on are the feet the encounter keeps.
        for (int guard = 0; guard < 20; guard++) battle.tick();
        check(!battle.terrainFallActive(), "no new fall starts from the landed feet");
        check(close(owner.getY(), cell.y()), "the landed height is stable: " + owner.getY());
        check(close(owner.getX(), exactX) && close(owner.getZ(), exactZ),
                "the landed lateral feet are stable: " + owner.getX() + "," + owner.getZ());
        check(battle.playerCell().equals(cell), "and the authoritative cell is unchanged: " + battle.playerCell());
        check(owner.getHealth() == health, "no delayed fall damage either: " + owner.getHealth());

        // The common case, as a control: a push that ends on the floor is not a fall at all. Without this the
        // hand-back could pass by starting a fall for every displacement instead of only for a real one.
        check(battle.fixtureAdoptPush(owner, new Vec3(0.2D, 0.0D, 0.0D)),
                "a push along the floor is accepted too");
        check(close(owner.getY(), cell.y()), "and it keeps the body on the surface: " + owner.getY());
        check(!battle.terrainFallActive(), "a push that ends on a real floor starts no fall");
        for (int guard = 0; guard < 5; guard++) battle.tick();
        check(!battle.terrainFallActive() && close(owner.getY(), cell.y()),
                "and it does not drift down afterwards: " + owner.getY());
    }

    /**
     * The same bridge over a real pit: the floor under the whole body is five cells lower.
     *
     * <p>A precise fall is not "descend to the floor of my cell". The real support under the whole footprint can
     * be anywhere below it, so the descent has to resolve it, count the whole cells it really fell and hand that
     * count to the existing damage rule - the very rule the ordinary forced fall already uses.</p>
     */
    private static void subCellPushOverAPit() {
        ensureBreakAffordable();
        settle();
        GridPos cell = pitCell();
        int depth = 12;
        for (int step = 1; step <= depth; step++) {
            level.setBlock(new BlockPos(cell.x(), cell.y() - step, cell.z()), air(), 3);
        }
        level.setBlock(new BlockPos(cell.x(), cell.y() - depth - 1, cell.z()),
                Blocks.STONE.defaultBlockState(), 3);
        owner.setPos(cell.x() + .5D, cell.y(), cell.z() + .5D);
        owner.setDeltaMovement(Vec3.ZERO);
        owner.setHealth(owner.getMaxHealth());
        float health = owner.getHealth();
        int ap = battle.currentAp();
        check(battle.fixtureAdoptPush(owner, new Vec3(0.0D, 0.601D, 0.0D)),
                "the push over the pit is accepted");
        check(close(owner.getY(), cell.y() + 0.601D), "the body hangs over the pit: " + owner.getY());
        check(battle.terrainFallActive(), "the pit under the real feet starts a fall");
        check(level.getBlockState(new BlockPos(cell.x(), cell.y() - 1, cell.z())).isAir(),
                "and the integer cell below really is empty, so this is not the integer rule");

        List<Double> heights = new ArrayList<>();
        for (int guard = 0; guard < 80 && battle.terrainFallActive(); guard++) {
            battle.tick();
            heights.add(owner.getY());
            check(close(owner.getX(), cell.x() + .5D) && close(owner.getZ(), cell.z() + .5D),
                    "the pit descent keeps the lateral anchor");
        }
        check(!battle.terrainFallActive(), "the deeper fall settles");
        check(heights.size() >= depth * 2, "a " + depth + "-cell precise descent is slice by slice: "
                + heights.size());
        check(close(owner.getY(), cell.y() - depth), "it lands on the pit floor: " + owner.getY());
        check(battle.playerCell().equals(new GridPos(cell.x(), cell.y() - depth, cell.z())),
                "the authoritative cell followed the whole cells it really fell: " + battle.playerCell());
        // The existing policy is preserved, not re-decided: the whole cells it really fell are what the shared
        // landing settlement receives, and the vanilla fall calculation turns them into damage.
        int fell = cell.y() - battle.playerCell().y();
        check(fell == depth, "the whole-cell count is the real descent: " + fell);
        check(owner.getHealth() < health,
                "the whole-cell descent reaches the existing damage rule: " + health + " -> " + owner.getHealth());
        check(owner.isAlive(), "a " + depth + "-block drop is survivable");
        check(battle.currentAp() == ap, "and it still charged no AP");
    }

    /**
     * A compound block is not one height: only its parts that really overlap the footprint are floor.
     *
     * <p>Two stairs stand side by side with their tall halves pointing away from each other, and the body stands
     * on the junction of their low treads. The footprint covers both low treads and neither tall half, so the
     * real floor there is the tread top - a surface that is neither the block's own maximum nor the height of the
     * cell below the body. Both ways the old whole-block answer got this wrong are checked on a live server: a
     * body already standing on the tread must not be told it hangs (the tread is not skipped because a taller
     * part of the same block exists elsewhere), and a body level with the tall halves must not be told it stands
     * (the tall half is not contact when it is outside the footprint and above the real feet).</p>
     */
    private static void compoundTreadUnderTheFootprint() {
        ensureBreakAffordable();
        settle();
        GridPos cell = battle.playerCell();
        check(cell != null, "the acting member has an authoritative cell");
        flatCell(cell);
        BlockPos east = new BlockPos(cell.x(), cell.y() - 1, cell.z());
        BlockPos west = east.west();
        level.setBlock(east, Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST), 3);
        level.setBlock(west, Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST), 3);
        // The exact feet this stage drives to, and the geometry it depends on, checked instead of assumed.
        double tread = cell.y() - 0.5D;
        double junctionX = cell.x();
        double z = cell.z() + 0.5D;
        AABB footprint = new AABB(junctionX - 0.3D, tread, z - 0.3D, junctionX + 0.3D, tread, z - 0.3D + 0.6D);
        for (BlockPos stair : List.of(east, west)) {
            check(!level.getBlockState(stair).getCollisionShape(level, stair).isEmpty(),
                    "the compound block really has collision shapes: " + stair);
            for (AABB part : level.getBlockState(stair).getCollisionShape(level, stair).toAabbs()) {
                AABB world = part.move(stair.getX(), stair.getY(), stair.getZ());
                boolean overlaps = world.maxX > footprint.minX + 1.0E-9D && world.minX < footprint.maxX - 1.0E-9D
                        && world.maxZ > footprint.minZ + 1.0E-9D && world.minZ < footprint.maxZ - 1.0E-9D;
                if (!overlaps) continue;
                check(close(world.maxY, tread),
                        "only the low tread overlaps the footprint: part top=" + world.maxY + " expected=" + tread);
            }
        }

        // First: standing exactly on that tread is support, so no fall may start and nothing may be charged.
        owner.setPos(junctionX - 0.05D, tread, z);
        owner.setDeltaMovement(Vec3.ZERO);
        owner.setHealth(owner.getMaxHealth());
        owner.removeEffect(MobEffects.LEVITATION);
        float health = owner.getHealth();
        int ap = battle.currentAp();
        int paid = battle.successfulPaidActions();
        int partnerPaid = battle.fixtureMemberPaid(partner);
        check(!battle.terrainFallActive(), "the compound stage starts with no fall in flight");
        check(battle.fixtureAdoptPush(owner, new Vec3(0.05D, 0.0D, 0.0D)),
                "the push onto the junction of the two treads is accepted");
        check(close(owner.getX(), junctionX) && close(owner.getZ(), z),
                "the accepted feet are the junction: " + owner.getX() + "," + owner.getZ());
        check(close(owner.getY(), tread), "the body stands exactly on the low treads: " + owner.getY());
        check(!battle.terrainFallActive(),
                "a body standing on a compound tread whose tall halves are outside its footprint is supported");
        check(owner.getHealth() == health && battle.currentAp() == ap
                        && battle.successfulPaidActions() == paid && battle.fixtureMemberPaid(partner) == partnerPaid,
                "the accepted push onto the tread charged nothing at all");
        for (int guard = 0; guard < 10; guard++) battle.tick();
        check(!battle.terrainFallActive() && close(owner.getY(), tread)
                        && close(owner.getX(), junctionX) && close(owner.getZ(), z),
                "and it keeps standing on the tread: " + owner.getY() + " at " + owner.getX() + "," + owner.getZ());

        // Second: level with the tall halves, the body is half a block above its own tread and must really fall
        // onto it - the tall part is not a floor at any height.
        settle();
        owner.setPos(junctionX - 0.05D, cell.y(), z);
        owner.setDeltaMovement(Vec3.ZERO);
        owner.setHealth(owner.getMaxHealth());
        owner.removeEffect(MobEffects.LEVITATION);
        health = owner.getHealth();
        ap = battle.currentAp();
        check(battle.fixtureAdoptPush(owner, new Vec3(0.05D, 0.0D, 0.0D)),
                "the push level with the tall halves is accepted");
        check(close(owner.getX(), junctionX) && close(owner.getY(), cell.y()),
                "the body is level with the tall halves: " + owner.getX() + "," + owner.getY());
        check(battle.terrainFallActive(),
                "a body level with the tall halves but above its own tread really falls");
        for (int guard = 0; guard < 40 && battle.terrainFallActive(); guard++) {
            battle.tick();
            check(close(owner.getX(), junctionX) && close(owner.getZ(), z),
                    "the compound descent keeps the lateral feet: " + owner.getX() + "," + owner.getZ());
        }
        check(!battle.terrainFallActive(), "the compound descent settles");
        check(close(owner.getY(), tread),
                "it lands on the low tread, not on the tall half's height: " + owner.getY() + " expected=" + tread);
        check(!close(owner.getY(), cell.y()), "and never stops at the tall half: " + owner.getY());

        // Third: the same compound floor from a full cell up, so the descent has to be several bounded slices and
        // has to pass the tall half's own height without stopping there.
        settle();
        owner.setPos(junctionX - 0.05D, cell.y() + 1.0D, z);
        owner.setDeltaMovement(Vec3.ZERO);
        owner.setHealth(owner.getMaxHealth());
        owner.removeEffect(MobEffects.LEVITATION);
        health = owner.getHealth();
        ap = battle.currentAp();
        check(battle.fixtureAdoptPush(owner, new Vec3(0.05D, 0.0D, 0.0D)),
                "the push higher above the tread is accepted");
        check(close(owner.getY(), cell.y() + 1.0D), "the body is a full cell above the tread: " + owner.getY());
        check(battle.terrainFallActive(), "the higher unsupported feet really fall");
        List<Double> heights = new ArrayList<>();
        for (int guard = 0; guard < 60 && battle.terrainFallActive(); guard++) {
            battle.tick();
            heights.add(owner.getY());
            check(close(owner.getX(), junctionX) && close(owner.getZ(), z),
                    "the higher compound descent keeps the lateral feet: " + owner.getX() + "," + owner.getZ());
        }
        check(!battle.terrainFallActive(), "the higher compound descent settles");
        check(heights.size() >= 2, "it descends in bounded slices, not one snap: " + heights);
        for (int index = 1; index < heights.size(); index++) {
            check(heights.get(index) < heights.get(index - 1),
                    "the compound descent is monotonic: " + heights.get(index - 1) + " -> " + heights.get(index));
        }
        check(close(owner.getY(), tread),
                "it lands on the low tread from higher up too: " + owner.getY() + " expected=" + tread);
        check(!close(owner.getY(), cell.y()), "and it never stopped at the tall half on the way down");
        // The descent really crossed a whole cell, so the existing whole-cell settlement rule receives it; that
        // rule's own native three-cell grace is what decides the damage, and this case stays below it.
        int fell = cell.y() + 1 - battle.playerCell().y();
        check(fell == 2, "the whole-cell count is the real crossing: " + fell);
        check(owner.getHealth() == health,
                "a two-cell compound drop stays inside the existing grace: " + health + " -> " + owner.getHealth());
        check(battle.currentAp() == ap, "and it charged no AP");
    }

    /**
     * A live levitation effect is a native cause, not a floor: it must never be turned into a descent.
     *
     * <p>Then, with the effect gone, the very same feet are handed to the fall rule. This is what proves the
     * guard is the effect itself and not "never start a fall after a push".</p>
     */
    private static void liveLevitationIsNotDropped() {
        settle();
        GridPos cell = battle.playerCell();
        flatCell(cell);
        owner.setPos(cell.x() + .5D, cell.y(), cell.z() + .5D);
        owner.setDeltaMovement(Vec3.ZERO);
        owner.setHealth(owner.getMaxHealth());
        owner.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 1200, 0));
        check(battle.fixtureAdoptPush(owner, new Vec3(0.0D, 0.601D, 0.0D)),
                "the push under levitation is accepted");
        check(close(owner.getY(), cell.y() + 0.601D), "the body is held at the fractional height");
        check(!battle.terrainFallActive(), "a live levitation effect is never turned into a fall");
        for (int guard = 0; guard < 5; guard++) battle.tick();
        check(!battle.terrainFallActive(), "and it stays that way while the native effect lasts");
        owner.removeEffect(MobEffects.LEVITATION);
        check(battle.fixtureAdoptPush(owner, new Vec3(0.0D, 0.0D, -0.2D)),
                "with the effect gone the same feet accept another push");
        check(battle.terrainFallActive(), "and this one really starts a fall");
        settle();
        check(!battle.terrainFallActive(), "the fall settles after the effect is gone");
        check(close(owner.getY(), cell.y()), "it lands on the real surface: " + owner.getY());
    }

    /**
     * The same bridge for an ordinary ground AI recipient, if this fixture can admit one.
     *
     * <p>A member and an AI unit settle through one seat, so the hand-back must not be member-only. The recipient
     * is a native zombie with no vanilla AI, admitted through the encounter's own admission transaction; if this
     * fixture cannot admit it, the sub-case is reported as not covered rather than counted as a pass.</p>
     */
    private static void groundRecipientThroughTheBridge() {
        settle();
        Zombie recipient = EntityType.ZOMBIE.create(level);
        check(recipient != null, "the ground recipient was created");
        recipient.setPos(origin.getX() + 4.5D, origin.getY(), origin.getZ() + 4.5D);
        recipient.setNoAi(true);
        recipient.setPersistenceRequired();
        recipient.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        recipient.setHealth(1000);
        level.addFreshEntity(recipient);
        spawned.add(recipient);
        if (!battle.tryAbsorb(recipient)) {
            Tactical.LOGGER.warn("[Tactical] BLOCK_TERRAIN SKIP a ground AI recipient could not be admitted "
                    + "by this fixture; the bridge is proved for the member path only here");
            recipient.discard();
            return;
        }
        try {
            ServerBattle.EnemyView before = battle.fixtureEnemy(recipient.getUUID());
            check(before != null, "the recipient really is a participant");
            GridPos cell = before.cell();
            check(cell != null, "the recipient has an authoritative cell");
            int ap = before.ap();
            double x = recipient.getX();
            double z = recipient.getZ();
            check(battle.fixtureAdoptPush(recipient, new Vec3(0.15D, 0.601D, 0.0D)),
                    "the push on the AI recipient is accepted");
            check(battle.terrainFallActive(), "the unsupported AI feet start a fall through the same bridge");
            for (int guard = 0; guard < 40 && battle.terrainFallActive(); guard++) {
                battle.tick();
                check(close(recipient.getX(), x + 0.15D) && close(recipient.getZ(), z),
                        "the AI descent keeps its real lateral position");
            }
            check(!battle.terrainFallActive(), "the AI fall settles");
            check(close(recipient.getY(), cell.y()), "it lands on the real surface: " + recipient.getY());
            ServerBattle.EnemyView after = battle.fixtureEnemy(recipient.getUUID());
            check(after != null && after.ap() == ap, "the AI fall charged no AP");
            check(after != null && after.paidActions() == before.paidActions(), "and counted no action");
        } finally {
            recipient.discard();
            for (int guard = 0; guard < 20 && battle.fixtureEnemy(recipient.getUUID()) != null; guard++) {
                battle.tick();
            }
            check(battle.fixtureEnemy(recipient.getUUID()) == null,
                    "the fixture recipient really left the encounter again");
        }
    }

    /** Placement still works, and the item command is no longer a second way to place. */
    private static void placementAndTheItemEntry() {
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 8));
        BlockPos target = cellAbove(2, 0);
        int ap = battle.currentAp();
        int count = battle.successfulPaidActions();
        int placeCost = battle.currentPlaceCost();
        // The anchor is the block the player clicks - the floor under the empty cell - and the hit point is
        // that block's real top surface, which is also the bottom plane of the cell being filled. Clicking the
        // empty cell itself with an UP face would describe a point that is not on any shape.
        BlockPos anchor = target.below();
        battle.placeBlock(owner.getInventory().selected, anchor, Direction.UP,
                new Vec3(target.getX() + .5D, target.getY(), target.getZ() + .5D));
        check(level.getBlockState(target).is(Blocks.STONE), "the native placement chain changed the world");
        check(owner.getMainHandItem().getCount() == 7, "one block left the hand");
        check(battle.currentAp() == ap - placeCost && battle.successfulPaidActions() == count + 1,
                "a placement commits its own quote exactly once");

        // The item command must not be a second, differently priced path to the same world change.
        BlockPos itemTarget = cellAbove(3, 0);
        int apAfter = battle.currentAp();
        int countAfter = battle.successfulPaidActions();
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 4));
        battle.useItem(owner.getInventory().selected);
        check(level.getBlockState(itemTarget).isAir(), "the item command changed nothing");
        check(battle.currentAp() == apAfter && battle.successfulPaidActions() == countAfter,
                "the refused item placement spent nothing");
        level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
    }

    /**
     * A hit on a real block shape, not on an assumed cube.
     *
     * <p>The isometric camera picks the outline the client draws, so a bottom slab's top face is half a
     * block up: a rule that insisted on {@code pos.y + 1} would refuse exactly the placement the player was
     * shown a highlight for. The same rule must still refuse a point that is not on the shape at all.</p>
     */
    private static void partialBlockHit() {
        BlockPos slabPos = cellAbove(4, -3);
        level.setBlock(slabPos, Blocks.STONE_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM), 3);
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 8));
        int ap = battle.currentAp();
        int count = battle.successfulPaidActions();
        int placeCost = battle.currentPlaceCost();
        battle.placeBlock(owner.getInventory().selected, slabPos, Direction.UP,
                new Vec3(slabPos.getX() + .5D, slabPos.getY() + .5D, slabPos.getZ() + .5D));
        check(level.getBlockState(slabPos.above()).is(Blocks.STONE),
                "a hit on the real slab surface placed the block above it");
        check(owner.getMainHandItem().getCount() == 7, "the slab placement took exactly one block");
        check(battle.currentAp() == ap - placeCost && battle.successfulPaidActions() == count + 1,
                "a surface hit is priced like any other placement");
        level.setBlock(slabPos.above(), Blocks.AIR.defaultBlockState(), 3);

        // The cell's own top is where a full cube would end and is not on the slab's shape at all.
        int afterAp = battle.currentAp();
        int afterCount = battle.successfulPaidActions();
        int held = owner.getMainHandItem().getCount();
        battle.placeBlock(owner.getInventory().selected, slabPos, Direction.UP,
                new Vec3(slabPos.getX() + .5D, slabPos.getY() + 1.0D, slabPos.getZ() + .5D));
        check(level.getBlockState(slabPos.above()).isAir(), "a hit that is not on the shape placed nothing");
        check(owner.getMainHandItem().getCount() == held, "and it consumed no item");
        check(battle.currentAp() == afterAp && battle.successfulPaidActions() == afterCount,
                "the refused hit spent nothing");
        level.setBlock(slabPos, Blocks.AIR.defaultBlockState(), 3);
    }

    /**
     * A block inserted while a body is between two cells stops it there.
     *
     * <p>The integer plan is re-read every tick, but a block can enter the segment a body is already moving
     * through. The descent must not be set straight through it, must not restart its deadline, and must
     * come to rest on the new block instead of arriving at the bottom of the shaft.</p>
     */
    private static void blockedMidStep() {
        ensureBreakAffordable();
        settle();
        GridPos cell = battle.playerCell();
        buildShaft(cell, 5);
        BlockPos support = new BlockPos(cell.x(), cell.y() - 1, cell.z());
        level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
        owner.setPos(cell.x() + .5D, cell.y(), cell.z() + .5D);
        owner.setHealth(owner.getMaxHealth());
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        int ap = battle.currentAp();
        int count = battle.successfulPaidActions();
        int price = battle.currentBreakCost();
        battle.breakBlock(owner.getInventory().selected, support);
        check(battle.terrainFallActive(), "the drop started a fall");

        // Let exactly one cell land, so the following step is a real segment already in flight.
        for (int guard = 0; guard < 40 && battle.terrainFallActive()
                && battle.playerCell().y() == cell.y(); guard++) {
            battle.tick();
        }
        GridPos landed = battle.playerCell();
        check(landed.y() == cell.y() - 1, "the first cell of the fall landed: " + landed);
        check(battle.terrainFallActive(), "the fall is still running between two cells");

        BlockPos obstacle = new BlockPos(landed.x(), landed.y() - 1, landed.z());
        level.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 3);
        settle();
        check(level.getBlockState(obstacle).is(Blocks.STONE), "the inserted block survived the descent");
        check(battle.playerCell().equals(landed),
                "the body came to rest on the inserted block instead of being pushed through it: "
                        + battle.playerCell());
        check(owner.getY() >= landed.y() - 0.01D, "the body is not inside the inserted block: " + owner.getY());
        check(battle.currentAp() == ap - price && battle.successfulPaidActions() == count + 1,
                "the interrupted fall charged nothing beyond the break that started it");
        level.setBlock(obstacle, Blocks.AIR.defaultBlockState(), 3);
    }

    /** A break with the right tool, and a break that is legal bare-handed. */
    private static void breaking() {
        BlockPos stone = cellAbove(0, 2);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        owner.setItemInHand(InteractionHand.MAIN_HAND, pickaxe);
        int damage = pickaxe.getDamageValue();
        int ap = battle.currentAp();
        int count = battle.successfulPaidActions();
        int price = battle.currentBreakCost();
        battle.breakBlock(owner.getInventory().selected, stone);
        check(level.getBlockState(stone).isAir(), "the block is really gone");
        check(owner.getMainHandItem().getDamageValue() == damage + 1,
                "the native tool durability was applied by the vanilla chain, not by this mod");
        check(battle.currentAp() == ap - price && battle.successfulPaidActions() == count + 1,
                "a successful break commits its own quote exactly once");
        check(battle.currentBreakCost() > price, "the shared success counter moved the break quote");

        // Dirt does not require a correct tool, so an empty hand may break it.
        BlockPos dirt = cellAbove(-2, 2);
        level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), 3);
        owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        int bareAp = battle.currentAp();
        int bareCount = battle.successfulPaidActions();
        int barePrice = battle.currentBreakCost();
        battle.breakBlock(owner.getInventory().selected, dirt);
        check(level.getBlockState(dirt).isAir(), "a hand-harvestable block may be broken bare-handed");
        check(battle.currentAp() == bareAp - barePrice && battle.successfulPaidActions() == bareCount + 1,
                "the bare-handed break is priced like any other");
    }

    /** Every refusal: no world change, no AP and no success counter. */
    private static void refusals() {
        BlockPos guarded = cellAbove(4, 2);
        level.setBlock(guarded, Blocks.STONE.defaultBlockState(), 3);
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SHOVEL));
        refused(guarded, "a block that needs the right tool");

        int restore = battle.currentAp();
        battle.adjustAp(-restore);
        check(battle.currentAp() == 0, "the fixture drained the AP of the acting member");
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        refused(guarded, "not enough AP");
        battle.adjustAp(restore);

        BlockPos far = new BlockPos(origin.getX() + 40, origin.getY(), origin.getZ() + 40);
        level.setBlock(far, Blocks.STONE.defaultBlockState(), 3);
        refused(far, "a block beyond the tactical reach");
        level.setBlock(far, Blocks.AIR.defaultBlockState(), 3);

        protectedPos = guarded;
        refused(guarded, "a cancelled break event");
        protectedPos = null;
        check(level.getBlockState(guarded).is(Blocks.STONE), "the protected block survived");
        level.setBlock(guarded, Blocks.AIR.defaultBlockState(), 3);

        // A block in the main hand places; a stale break intent that arrives with one is refused by the
        // server itself, so the old mode never becomes a second, unpriced way to break.
        BlockPos heldTarget = cellAbove(-3, 2);
        level.setBlock(heldTarget, Blocks.STONE.defaultBlockState(), 3);
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 8));
        int stoneAp = battle.currentAp();
        int stoneCount = battle.successfulPaidActions();
        battle.breakBlock(owner.getInventory().selected, heldTarget);
        check(level.getBlockState(heldTarget).is(Blocks.STONE),
                "a block in the main hand cannot break anything");
        check(owner.getMainHandItem().getCount() == 8, "and the refused intent took no block from it");
        check(battle.currentAp() == stoneAp && battle.successfulPaidActions() == stoneCount,
                "the refused break spent nothing");
        level.setBlock(heldTarget, Blocks.AIR.defaultBlockState(), 3);

        // A block one level up and to the side is plainly visible from the actor's eye, so the block
        // command's own sight test must accept it - the entity sight test would have refused it.
        BlockPos high = new BlockPos(origin.getX() + 1, origin.getY() + 1, origin.getZ());
        level.setBlock(high, Blocks.STONE.defaultBlockState(), 3);
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        battle.breakBlock(owner.getInventory().selected, high);
        check(level.getBlockState(high).isAir(), "a visible block one level up may be broken");

        // The same block behind a solid neighbour is not in sight, and nothing may be spent on it. The
        // occluder sits at eye height one cell away, where the ray to the target's centre really crosses it.
        BlockPos hidden = new BlockPos(origin.getX() + 3, origin.getY(), origin.getZ());
        BlockPos occluder = new BlockPos(origin.getX() + 1, origin.getY() + 1, origin.getZ());
        level.setBlock(hidden, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(occluder, Blocks.STONE.defaultBlockState(), 3);
        refused(hidden, "a block hidden behind another block");
        check(level.getBlockState(hidden).is(Blocks.STONE), "the hidden block survived the refusal");
        level.setBlock(hidden, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(occluder, Blocks.AIR.defaultBlockState(), 3);

        // A second member: it is a real member of this encounter, but it is not the acting unit.
        check(BattleManager.invite(owner, partner) == BattleEligibility.Denial.NONE,
                "the second member is invited");
        check(battle.joinHeadless(partner) == BattleEligibility.Denial.NONE,
                "the second member joins the encounter");
        BlockPos other = cellAbove(1, 3);
        level.setBlock(other, Blocks.STONE.defaultBlockState(), 3);
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        int ap = battle.currentAp();
        int count = battle.successfulPaidActions();
        battle.breakBlock(partner, battle.stamp(), partner.getInventory().selected, other);
        check(level.getBlockState(other).is(Blocks.STONE), "a waiting member changed no block");
        check(battle.currentAp() == ap && battle.successfulPaidActions() == count,
                "a waiting member spent nothing");

        battle.breakBlock(owner, new ActionStamp(UUID.randomUUID(), 1L), owner.getInventory().selected, other);
        check(level.getBlockState(other).is(Blocks.STONE), "a stale stamp changed no block");
        check(battle.currentAp() == ap && battle.successfulPaidActions() == count,
                "a stale stamp spent nothing");
        level.setBlock(other, Blocks.AIR.defaultBlockState(), 3);
    }

    /**
     * The forced fall of the acting player, settled through the vanilla landing chain.
     *
     * <p>The support under the owner is a real block: breaking it must drop the owner to the first legal
     * landing inside the zone, update the authoritative cell, and cost nothing on top of the break.</p>
     */
    private static void forcedFall() {
        ensureBreakAffordable();
        GridPos cell = battle.playerCell();
        int depth = 4;
        buildShaft(cell, depth);
        BlockPos support = new BlockPos(cell.x(), cell.y() - 1, cell.z());
        level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
        owner.setPos(cell.x() + .5D, cell.y(), cell.z() + .5D);
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        owner.setHealth(owner.getMaxHealth());
        float before = owner.getHealth();
        int ap = battle.currentAp();
        int count = battle.successfulPaidActions();
        int price = battle.currentBreakCost();
        battle.breakBlock(owner.getInventory().selected, support);
        check(level.getBlockState(support).isAir(), "the support was really broken");
        int apAfterBreak = battle.currentAp();
        int countAfterBreak = battle.successfulPaidActions();
        check(apAfterBreak == ap - price && countAfterBreak == count + 1,
                "the break that started the fall was priced once");
        check(battle.terrainFallActive(), "losing the support starts a real forced fall");

        settle();
        check(!battle.terrainFallActive(), "the fall settles inside the encounter");
        check(battle.playerCell().equals(new GridPos(cell.x(), cell.y() - depth, cell.z())),
                "the authoritative cell followed the fall: " + battle.playerCell());
        check(Math.abs(owner.getY() - (cell.y() - depth)) < 0.01D, "the body really moved: " + owner.getY());
        check(battle.currentAp() == apAfterBreak && battle.successfulPaidActions() == countAfterBreak,
                "the fall itself costs no AP and counts as no action");
        check(owner.getHealth() < before, "a real four-block drop goes through the vanilla fall damage");
        check(owner.isAlive(), "the drop is survivable");

        // Slow falling: the same drop, no health lost. Vanilla decides that while travelling; a scripted
        // descent has to keep the same rule instead of inventing one.
        owner.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 1200, 0));
        float slowFalling = fallTrial(4, "slow falling");
        check(slowFalling <= 0.0F, "slow falling makes the landing free: " + slowFalling);
        owner.removeEffect(MobEffects.SLOW_FALLING);
        owner.setHealth(owner.getMaxHealth());

        // Feather falling on a longer drop: strictly less damage than the same drop without it.
        float plain = fallTrial(8, "plain eight-block drop");
        check(plain > 0.0F, "an eight-block drop hurts without protection: " + plain);
        ItemStack boots = featherFallingBoots(4);
        owner.setItemSlot(EquipmentSlot.FEET, boots);
        float softened = fallTrial(8, "feather falling drop");
        check(softened < plain, "feather falling reduces the same drop: " + softened + " < " + plain);
        owner.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        owner.setHealth(owner.getMaxHealth());
    }

    /** A shaft with no landing inside the zone is refused before anything is broken. */
    private static void noLandingRefusal() {
        ensureBreakAffordable();
        GridPos cell = battle.playerCell();
        var bounds = battle.zoneBounds();
        int depth = (int) Math.ceil(cell.y() - bounds.minY) + 2;
        check(depth > 2 && depth < 100, "the zone is deep enough to build an out-of-zone shaft: " + depth);
        buildShaft(cell, depth);
        BlockPos support = new BlockPos(cell.x(), cell.y() - 1, cell.z());
        level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
        owner.setPos(cell.x() + .5D, cell.y(), cell.z() + .5D);
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        int ap = battle.currentAp();
        int count = battle.successfulPaidActions();
        battle.breakBlock(owner.getInventory().selected, support);
        check(level.getBlockState(support).is(Blocks.STONE),
                "the support is not broken when the landing would leave the zone");
        check(battle.currentAp() == ap && battle.successfulPaidActions() == count,
                "the refused break spent nothing");
        check(!battle.terrainFallActive(), "the refused break starts no fall");
        // The refused break must still be usable once the column has a legal landing again.
        level.setBlock(new BlockPos(cell.x(), cell.y() - 2, cell.z()), Blocks.STONE.defaultBlockState(), 3);
        battle.breakBlock(owner.getInventory().selected, support);
        check(level.getBlockState(support).is(Blocks.AIR), "with a legal landing the same break succeeds");
        settle();
        check(!battle.terrainFallActive() && battle.playerCell().equals(new GridPos(cell.x(), cell.y() - 1, cell.z())),
                "one cell of drop lands on the restored floor: " + battle.playerCell());
        // Solid ground again for the remaining stages: the owner stands at cell.y - 1, so the floor it
        // needs is one cell lower and its own cell stays open.
        for (int step = 2; step <= depth + 1; step++) {
            level.setBlock(new BlockPos(cell.x(), cell.y() - step, cell.z()), Blocks.STONE.defaultBlockState(), 3);
        }
        owner.setPos(cell.x() + .5D, cell.y() - 1, cell.z() + .5D);
        attemptCell(battle.playerCell(), new GridPos(cell.x(), cell.y() - 1, cell.z()));
    }

    /** A unit that carries itself is never dropped by a missing floor. */
    private static void flyingUnitsAreNotAffected() {
        Phantom phantom = EntityType.PHANTOM.create(level);
        check(phantom != null, "the flying unit exists");
        phantom.setPos(origin.getX() + 7.5D, origin.getY() + 6, origin.getZ() + 7.5D);
        phantom.setNoAi(true);
        phantom.setPersistenceRequired();
        phantom.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        phantom.setHealth(1000);
        level.addFreshEntity(phantom);
        spawned.add(phantom);
        check(battle.tryAbsorb(phantom), "the flying unit joins the encounter");
        for (int guard = 0; guard < 8 && !battle.finished(); guard++) battle.tick();
        check(!battle.terrainFallActive(),
                "a flying unit whose cell has no floor under it is not dropped");
    }

    /**
     * A landing that kills: the member leaves the encounter and no fall state stays behind.
     *
     * <p>The victim is the acting member itself, because that is the case the encounter must survive: the
     * queue's current entry dies in mid-air, its own break paid for the fall, and the remaining member has to
     * carry on without a fall state or a stuck queue entry. Breaking a *distant* member's support is not this
     * stage's subject and the block commands deliberately only reach sixteen blocks.</p>
     */
    private static void fallDeath() {
        ensureBreakAffordable();
        settle();
        GridPos cell = battle.playerCell();
        check(cell != null, "the acting member has an authoritative cell");
        int members = battle.memberCount();
        buildShaft(cell, 20);
        BlockPos support = new BlockPos(cell.x(), cell.y() - 1, cell.z());
        level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
        owner.setPos(cell.x() + .5D, cell.y(), cell.z() + .5D);
        owner.setHealth(1.0F);
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        int ap = battle.currentAp();
        int price = battle.currentBreakCost();
        battle.breakBlock(owner.getInventory().selected, support);
        check(level.getBlockState(support).is(Blocks.AIR), "the support under the acting member was broken");
        check(battle.currentAp() == ap - price, "the owner paid exactly the break");
        if (battle.terrainFallActive()) {
            settle();
        }
        for (int guard = 0; guard < 5 && !battle.finished(); guard++) battle.tick();
        check(!battle.terrainFallActive(), "no fall state survives a fatal landing");
        check(!owner.isAlive(), "the acting member died from the drop");
        check(battle.memberCount() < members, "a dead member left the encounter: " + battle.memberCount());
        if (battle.finished()) {
            Tactical.LOGGER.info("[Tactical] BLOCK_TERRAIN note: the encounter ended after the fatal fall");
        }

        // An encounter that ends while a unit is still in the air must not keep a fall behind: the state,
        // its reservations and its client interpolation all belong to the encounter that just ended.
        if (!battle.finished()) {
            GridPos ownerCell = battle.playerCell();
            if (ownerCell != null) {
                buildShaft(ownerCell, 3);
                BlockPos supportUnderOwner = new BlockPos(ownerCell.x(), ownerCell.y() - 1, ownerCell.z());
                level.setBlock(supportUnderOwner, Blocks.STONE.defaultBlockState(), 3);
                owner.setPos(ownerCell.x() + .5D, ownerCell.y(), ownerCell.z() + .5D);
                owner.setHealth(owner.getMaxHealth());
                owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
                battle.breakBlock(owner.getInventory().selected, supportUnderOwner);
                boolean falling = battle.terrainFallActive();
                battle.finish("fixture teardown");
                check(!battle.terrainFallActive(), "ending the encounter clears every fall it held");
                if (falling) {
                    Tactical.LOGGER.info("[Tactical] BLOCK_TERRAIN note: the encounter was ended mid-fall");
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    /** One exact-foot comparison: every precise receipt here is a number, never a rounded cell. */
    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < 1.0E-6D;
    }

    /**
     * A cell whose floor is the block directly under it and whose neighbourhood is open air.
     *
     * <p>The body box is wider than the push's own cell, so the four columns it can straddle have to be as flat
     * as the one it lands in; otherwise the real-support answer would legitimately be a neighbouring floor and
     * the stage would be measuring the fixture instead of the rule.</p>
     */
    private static void flatCell(GridPos cell) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(new BlockPos(cell.x() + dx, cell.y() - 1, cell.z() + dz),
                        Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 0; dy <= 2; dy++) {
                    level.setBlock(new BlockPos(cell.x() + dx, cell.y() + dy, cell.z() + dz), air(), 3);
                }
            }
        }
    }

    /** An untouched cell of the platform, so the pit stage never reuses a shaft an earlier stage dug. */
    private static GridPos pitCell() {
        return new GridPos(origin.getX() - 8, origin.getY(), origin.getZ() - 8);
    }

    private static void attemptCell(GridPos actual, GridPos expected) {
        check(actual.equals(expected), "the authoritative cell is where the fixture put it: " + actual);
    }

    private static void settle() {
        for (int guard = 0; guard < 600 && battle.terrainFallActive() && !battle.finished(); guard++) battle.tick();
    }

    /** One drop of {@code depth} cells and the health it costs; leaves the owner standing again. */
    private static float fallTrial(int depth, String label) {
        ensureBreakAffordable();
        settle();
        GridPos cell = battle.playerCell();
        buildShaft(cell, depth);
        BlockPos support = new BlockPos(cell.x(), cell.y() - 1, cell.z());
        level.setBlock(support, Blocks.STONE.defaultBlockState(), 3);
        owner.setPos(cell.x() + .5D, cell.y(), cell.z() + .5D);
        owner.setHealth(owner.getMaxHealth());
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        float before = owner.getHealth();
        battle.breakBlock(owner.getInventory().selected, support);
        check(battle.terrainFallActive(), label + ": the drop started a fall");
        settle();
        check(!battle.terrainFallActive(), label + ": the drop settled");
        check(battle.playerCell().y() == cell.y() - depth, label + ": the landing cell is the expected one");
        return before - owner.getHealth();
    }

    /**
     * A shaft under {@code cell}: air for the drop, a stone floor under the landing cell, and the two
     * cells the body occupies while falling left open.
     */
    private static void buildShaft(GridPos cell, int depth) {
        for (int step = 1; step <= depth + 1; step++) {
            level.setBlock(new BlockPos(cell.x(), cell.y() - step, cell.z()), air(), 3);
        }
        level.setBlock(new BlockPos(cell.x(), cell.y() - depth - 1, cell.z()),
                Blocks.STONE.defaultBlockState(), 3);
    }

    private static ItemStack featherFallingBoots(int value) {
        ItemStack boots = new ItemStack(Items.DIAMOND_BOOTS);
        Holder<Enchantment> feather = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .get(Enchantments.FEATHER_FALLING).orElse(null);
        if (feather == null) return boots;
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        mutable.set(feather, value);
        boots.set(net.minecraft.core.component.DataComponents.ENCHANTMENTS, mutable.toImmutable());
        return boots;
    }

    private static BlockPos cellAbove(int dx, int dz) {
        return new BlockPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
    }

    /** The world, the AP and the success counter must all be untouched by a refusal. */
    private static void refused(BlockPos pos, String label) {
        BlockState before = level.getBlockState(pos);
        int ap = battle.currentAp();
        int count = battle.successfulPaidActions();
        battle.breakBlock(owner.getInventory().selected, pos);
        check(level.getBlockState(pos) == before, label + ": the block is untouched");
        check(battle.currentAp() == ap && battle.successfulPaidActions() == count, label + ": nothing was spent");
    }

    /**
     * Makes sure the break that starts a fall is affordable.
     *
     * <p>These stages measure the fall - its landing, its damage and its cost relative to the break - not the
     * AP budget: the tiered break price rises with every use, so a long sequence runs the pool dry and the
     * break itself is refused, which looks exactly like a fall that never started. The pool is topped up to
     * its own maximum; the assertions still compare against an AP snapshot taken after this.</p>
     */
    private static void ensureBreakAffordable() {
        battle.adjustAp(battle.currentAp() < battle.currentBreakCost()
                ? battle.currentBreakCost() : 0);
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            // The journal names the reason a refused action gives, which is what a failure usually needs.
            String detail = wire == null || wire.log.isEmpty() ? "" : " (journal: " + wire.log + ")";
            Tactical.LOGGER.error("[Tactical] BLOCK_TERRAIN [{}] FAIL {}{}", checks + 1, label, detail);
            throw new AssertionError(label + detail);
        }
        checks++;
        Tactical.LOGGER.info("[Tactical] BLOCK_TERRAIN [{}] PASS {}", checks, label);
    }

    // ------------------------------------------------------------------ harness

    private static void setup(MinecraftServer server) {
        BattleManager.setAutoTrigger(false);
        // The damage trials drop the same actor four to eight blocks at a time, one after another, so the
        // encounter has to be taller than the default 16 blocks or the later trials dig out of the zone and
        // are refused before anything falls. This is the fixture's own rule set, restored in finish().
        defaultZoneHeight = TacticalConfig.INSTANCE.zoneHeight.get();
        TacticalConfig.INSTANCE.zoneHeight.set(48);
        level = server.overworld();
        level.setDayTime(18000);
        BlockPos spawn = level.getSharedSpawnPos();
        // Far enough from spawn that the platform owns its own chunks, and high enough to dig the deep
        // shafts the fall stages need.
        origin = new BlockPos(spawn.getX() + 240, 80, spawn.getZ() + 240);
        for (int x = (origin.getX() - 16) >> 4; x <= (origin.getX() + 16) >> 4; x++) {
            for (int z = (origin.getZ() - 16) >> 4; z <= (origin.getZ() + 16) >> 4; z++) {
                if (level.setChunkForced(x, z, true)) forced.add(new BlockPos(x, 0, z));
            }
        }
        // A flat platform with open air above it and solid stone below, so a shaft never opens into
        // terrain the stage did not build. The bulk fill uses update flag 2: it is a real world change,
        // but a headless fixture has no client to notify and the neighbour cascade is pure cost.
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y < 8; y++) level.setBlock(origin.offset(x, y, z), air(), 2);
                for (int y = 2; y <= 44; y++) level.setBlock(origin.offset(x, -y, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes("tactical-block-terrain-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "BlockOwner");
        owner = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        wire = new Wire();
        wire.channel = new EmbeddedChannel(wire);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph",
                "move_choice_offer")) {
            ChannelAttributes.getOrCreateAdHocChannels(wire)
                    .add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(server, wire, owner, CommonListenerCookie.createInitial(profile, false));
        owner.setPos(origin.getX() + .5D, origin.getY(), origin.getZ() + .5D);
        owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        owner.setHealth(1000);
        level.addNewPlayer(owner);
        partner = new FakePlayer(level, new GameProfile(
                UUID.nameUUIDFromBytes("tactical-block-terrain-partner".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "BlockPartner"));
        partner.setPos(origin.getX() + 2.5D, origin.getY(), origin.getZ() + 2.5D);
        partner.setHealth(20.0F);
        bystander = zombie(5, 5);
    }

    private static Zombie zombie(int dx, int dz) {
        Zombie mob = EntityType.ZOMBIE.create(level);
        check(mob != null, "the native bystander was created");
        mob.setPos(origin.getX() + dx + .5D, origin.getY(), origin.getZ() + dz + .5D);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        mob.setHealth(1000);
        level.addFreshEntity(mob);
        spawned.add(mob);
        return mob;
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        protectedPos = null;
        BattleManager.reset();
        for (Entity entity : spawned) entity.discard();
        spawned.clear();
        if (owner != null && !owner.isRemoved()) {
            level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
        }
        if (wire != null && wire.channel != null) wire.channel.finishAndReleaseAll();
        if (level != null) {
            for (BlockPos chunk : forced) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        }
        forced.clear();
        BattleEffects.clear();
        if (defaultZoneHeight > 0) {
            TacticalConfig.INSTANCE.zoneHeight.set(defaultZoneHeight);
            defaultZoneHeight = -1;
        }
        server.halt(false);
    }

    private static final class Wire extends Connection {
        EmbeddedChannel channel;
        BattleStatePacket state;
        /** Temporary diagnostic: the journal tail, so a refused action names its own reason. */
        final List<String> log = new ArrayList<>();

        Wire() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof BattleStatePacket next) {
                state = next;
            }
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof com.tactical.neoforge.network.BattleLogPacket next) {
                log.clear();
                for (com.tactical.neoforge.network.BattleLogPacket.Entry entry : next.history()) {
                    log.add(entry.category() + "=" + entry.text().getString());
                }
            }
        }
    }
}
