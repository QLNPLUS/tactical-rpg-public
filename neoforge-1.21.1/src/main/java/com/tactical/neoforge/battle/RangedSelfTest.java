package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.BattleLogPacket;
import com.tactical.neoforge.network.BattleStatePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Disposable-world dedicated-server integration fixture: {@code -Dtactical.selftest=ranged}. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class RangedSelfTest {
    private static boolean enabled;
    private static int ticks, stage, stageAt, playerShots, skeletonShots, ownerArrowHits;
    private static float targetHealth, ownerHealth;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static Skeleton skeleton;
    private static ServerBattle battle;
    private static RecordingConnection wire;
    private static final List<BlockPos> forced = new ArrayList<>();
    private static final Map<Integer, Integer> skeletonShotsByTurn = new HashMap<>();

    private RangedSelfTest() { }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "ranged".equals(System.getProperty("tactical.selftest"));
        ticks = stage = stageAt = playerShots = skeletonShots = ownerArrowHits = 0;
        skeletonShotsByTurn.clear();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onArrow(EntityJoinLevelEvent event) {
        if (!enabled || event.getLevel() != level || !(event.getEntity() instanceof AbstractArrow arrow)) return;
        if (arrow.getOwner() == owner) playerShots++;
        if (arrow.getOwner() == skeleton) {
            skeletonShots++;
            int turn = wire.state == null ? -1 : wire.state.round();
            skeletonShotsByTurn.merge(turn, 1, Integer::sum);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent.Post event) {
        if (enabled && event.getEntity() == owner && event.getSource().getEntity() == skeleton
                && event.getSource().getDirectEntity() instanceof AbstractArrow && event.getNewDamage() > 0) {
            ownerArrowHits++;
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled || ++ticks < 20) return;
        try {
            require(ticks < 1400, "ranged fixture completion deadline");
            if (stage == 0) {
                setup(event.getServer());
                stage = 1;
                return;
            }
            if (stage == 1) {
                // Real ServerPlayer spawn protection expires, and forced entity sections become visible.
                if (ticks < 100 || !level.getEntitiesOfClass(Skeleton.class, owner.getBoundingBox().inflate(12)).contains(skeleton)) return;
                for (Mob other : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(24))) {
                    if (other != skeleton && other instanceof net.minecraft.world.entity.monster.Enemy) other.discard();
                }
                require(BattleManager.start(owner, true), "player ranged encounter starts");
                battle = BattleManager.current();
                require(battle != null && battle.isPlayerTurnNow(), "high-initiative owner acts first");
                owner.setYRot(90); // Target is east: yaw -90 would face it.
                owner.setYHeadRot(90);
                targetHealth = skeleton.getHealth();
                int ap = battle.currentAp();
                int arrows = owner.getOffhandItem().getCount();
                int price = battle.currentAttackCost();
                battle.tryAttack(skeleton.getUUID());
                require(playerShots == 1 && battle.pendingProjectiles() == 1, "facing-away intent creates one actual tracked vanilla arrow");
                require(battle.currentAp() == ap - price && owner.getOffhandItem().getCount() == arrows - 1,
                        "successful bow shot spends AP and actual ammunition");
                stageAt = ticks;
                stage = 2;
            } else if (stage == 2) {
                if (battle.pendingProjectiles() > 0 || ticks - stageAt < 30) return;
                require(skeleton.getHealth() < targetHealth, "actual player arrow damages target after facing-away intent");
                require(battle.isPlayerTurnNow(), "player retains turn after projectile resolution");
                checkCoverAndRejection();
                BattleManager.reset();
                owner.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
                owner.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.1);
                owner.setHealth(owner.getMaxHealth());
                owner.invulnerableTime = 0;
                skeleton.setHealth(skeleton.getMaxHealth());
                skeleton.setPos(origin.getX() + 8.5, origin.getY(), origin.getZ() + .5);
                skeleton.setNoAi(false);
                require(BattleManager.start(owner, true), "skeleton ranged encounter starts");
                battle = BattleManager.current();
                require(battle != null && battle.isPlayerTurnNow(), "high-initiative owner starts skeleton scenario");
                ownerHealth = owner.getHealth();
                stage = 3;
                stageAt = ticks;
                battle.endPlayerTurn();
            } else if (stage == 3) {
                checkPendingEnemyShot();
                if (!battle.isPlayerTurnNow()) return;
                if (skeletonShots == 0) {
                    battle.endPlayerTurn();
                    return;
                }
                require(skeletonShots == 1 && ownerArrowHits == 1 && owner.getHealth() < ownerHealth,
                        "one actual vanilla skeleton arrow hits real owner before turn advances");
                require(battle.pendingProjectiles() == 0, "player turn begins only after skeleton projectile resolves");
                stageAt = ticks;
                stage = 4;
            } else if (stage == 4) {
                require(battle.isPlayerTurnNow() && skeletonShots == 1 && battle.pendingProjectiles() == 0,
                        "idle player turn never permits an extra wall-clock skeleton shot");
                if (ticks - stageAt < 110) return;
                owner.invulnerableTime = 0;
                battle.endPlayerTurn();
                stage = 5;
                stageAt = ticks;
            } else if (stage == 5) {
                checkPendingEnemyShot();
                if (!battle.isPlayerTurnNow()) return;
                if (skeletonShots == 1) {
                    battle.endPlayerTurn();
                    return;
                }
                require(skeletonShots == 2 && ownerArrowHits == 2 && skeletonShotsByTurn.size() == 2,
                        "two distinct own skeleton turns each fire exactly one further real arrow");
                Tactical.LOGGER.info("[Tactical] RANGED SELFTEST PASSED: facing-away player vanilla bow hit, low-cover valid arc, sealed-target rejection preserves AP/ammo/durability with journal reason, native skeleton arrow capture/actor lock/real owner damage, idle110ticks no extra shots, next-own-turn second shot");
                finish(event.getServer());
            }
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] RANGED SELFTEST FAILED at stage {} tick {}", stage, ticks, failure);
            finish(event.getServer());
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    private static void checkPendingEnemyShot() {
        require(!skeletonShotsByTurn.containsKey(-1)
                && skeletonShotsByTurn.values().stream().allMatch(count -> count == 1),
                "at most one shot per authoritative skeleton own turn");
        if (battle.pendingProjectiles() == 0) return;
        require(!battle.isPlayerTurnNow(), "pending enemy arrow cannot hand control to player");
        require(wire.state != null && wire.state.actorEntityId() == skeleton.getId(), "pending projectile keeps skeleton as current actor");
    }

    private static void checkCoverAndRejection() {
        GridPos from = battle.playerCell();
        // Low cover must not cause a blanket sight rejection when the actual arrow arc clears it.
        for (int z = -2; z <= 2; z++) {
            level.setBlock(origin.offset(4, 0, z), Blocks.STONE.defaultBlockState(), 3);
        }
        require(BallisticShot.plan(level, owner, from, skeleton, 3.0F, true, 0) != null,
                "ballistic planner permits a physically clear arc over low cover");
        for (int z = -2; z <= 2; z++) {
            level.setBlock(origin.offset(4, 0, z), Blocks.AIR.defaultBlockState(), 3);
        }
        // Fully seal the target: no low, high or sideways arc can enter its occupied interior.
        BlockPos target = skeleton.blockPosition();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y <= 3; y++) {
            if (Math.abs(x) == 1 || Math.abs(z) == 1 || y == 3) {
                level.setBlock(target.offset(x, y, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        int ap = battle.currentAp(), ammo = owner.getOffhandItem().getCount();
        int durability = owner.getMainHandItem().getDamageValue(), shots = playerShots;
        long sequence = wire.log == null ? 0 : wire.log.sequence();
        battle.tryAttack(skeleton.getUUID());
        require(battle.currentAp() == ap && owner.getOffhandItem().getCount() == ammo
                && owner.getMainHandItem().getDamageValue() == durability && playerShots == shots
                && battle.pendingProjectiles() == 0, "sealed-world rejection preserves AP/ammo/durability and creates no arrow");
        require(wire.log != null && wire.log.sequence() > sequence
                && wire.log.history().stream().anyMatch(entry -> entry.text().contains("弹道")),
                "authoritative rejection records its ballistic obstruction reason");
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y <= 3; y++) {
            level.setBlock(target.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int y = Math.min(level.getMaxBuildHeight() - 10, spawn.getY() + 20);
        origin = new BlockPos(spawn.getX(), y, spawn.getZ());
        for (int cx = (origin.getX() - 20) >> 4; cx <= (origin.getX() + 20) >> 4; cx++) {
            for (int cz = (origin.getZ() - 20) >> 4; cz <= (origin.getZ() + 20) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) forced.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) {
            level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            for (int dy = 0; dy < 6; dy++) level.setBlock(origin.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
        }
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes("tactical-ranged-selftest".getBytes(StandardCharsets.UTF_8)), "RangedOwner");
        owner = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        wire = new RecordingConnection();
        wire.channel = new EmbeddedChannel(wire);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph", "move_choice_offer")) {
            ChannelAttributes.getOrCreateAdHocChannels(wire).add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(server, wire, owner, CommonListenerCookie.createInitial(profile, false));
        owner.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
        owner.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        owner.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.1);
        owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        owner.setHealth(100);
        owner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        owner.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 32));
        level.addNewPlayer(owner);
        skeleton = EntityType.SKELETON.create(level);
        require(skeleton != null, "vanilla skeleton exists");
        skeleton.setPos(origin.getX() + 8.5, origin.getY(), origin.getZ() + .5);
        skeleton.setNoAi(true);
        skeleton.setPersistenceRequired();
        skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        skeleton.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        skeleton.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        skeleton.setHealth(100);
        require(level.addFreshEntity(skeleton), "skeleton registered in real server world");
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        BattleManager.reset();
        if (skeleton != null) skeleton.discard();
        if (owner != null && !owner.isRemoved()) level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
        if (wire != null && wire.channel != null) wire.channel.finishAndReleaseAll();
        if (level != null) for (BlockPos chunk : forced) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        forced.clear();
        BattleEffects.clear();
        server.halt(false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class RecordingConnection extends Connection {
        private EmbeddedChannel channel;
        private BattleStatePacket state;
        private BattleLogPacket log;
        private RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundCustomPayloadPacket custom) {
                if (custom.payload() instanceof BattleStatePacket next) state = next;
                if (custom.payload() instanceof BattleLogPacket next) log = next;
            }
        }
    }
}
