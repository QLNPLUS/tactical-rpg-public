package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridMovementRules;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.ai.*;
import com.tactical.neoforge.network.CreeperTelegraphPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Opt-in destructive-world fixture. Use only an isolated run directory with tactical.selftest=creeper. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class CreeperSelfTest {
    private static boolean enabled;
    private static int ticks;
    private static int stage;
    private static int preparedAt;
    private static int explosions;
    private static boolean allowDetonation;
    private static boolean oldMobGriefing;
    private static ServerLevel level;
    private static ServerPlayer player;
    private static ServerBattle encounter;
    private static Creeper creeper;
    private static Zombie witness;
    private static GridPos origin;
    private static final List<Mob> fixtures = new ArrayList<>();
    private static final List<ChunkPos> forced = new ArrayList<>();

    private CreeperSelfTest() { }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "creeper".equals(System.getProperty("tactical.selftest"));
        ticks = stage = preparedAt = explosions = 0;
        allowDetonation = false;
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Start event) {
        if (!enabled || creeper == null || event.getExplosion().getDirectSourceEntity() != creeper) return;
        require(allowDetonation, "no real-time/ignited vanilla tick detonation");
        require(encounter != null && "ENEMY_TURN".equals(encounter.phaseName()), "explosion occurs on an enemy own turn");
        require(!event.getExplosion().interactsWithBlocks(), "vanilla explosion honors mobGriefing=false");
        explosions++;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled || ++ticks < 20) return;
        try {
            require(ticks < 1500, "creeper fixture completion deadline");
            if (stage == 0) {
                setup(event.getServer());
                stage = 1;
                return;
            }
            if (stage == 1) {
                // Forced chunks take a tick to make their entity section visible.
                if (!level.getEntitiesOfClass(Creeper.class, player.getBoundingBox().inflate(16)).contains(creeper)) return;
                for (Mob stray : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(24))) {
                    if (stray instanceof net.minecraft.world.entity.monster.Enemy && !fixtures.contains(stray)) stray.discard();
                }
                require(BattleManager.start(player, true), "real battle starts");
                encounter = BattleManager.current();
                require(encounter != null && encounter.isPlayerTurnNow(), "high-initiative fixture player starts");
                require(access(creeper).tactical$isControlled(), "participant fuse control enabled");
                require(encounter.creeperSnapshot().creepers().size() == 1, "initial snapshot includes unprepared participant");
                creeper.ignite(); // Even explicit ignition must not advance the wall-clock fuse.
                encounter.endPlayerTurn();
                stage = 2;
            } else if (stage == 2) {
                require(creeper.isAlive() && !creeper.isRemoved() && explosions == 0, "approach never explodes early");
                if (access(creeper).tactical$isPreparing()) {
                    if (!encounter.isPlayerTurnNow()) return;
                    require(encounter.isPlayerTurnNow(), "preparation ends creeper turn");
                    GridPos cell = new GridPos(creeper.getBlockX(), creeper.getBlockY(), creeper.getBlockZ());
                    require(BlastWarningGeometry.inPreparationRange(cell, encounter.playerCell()), "creeper stops one to two cells away");
                    var snapshot = encounter.creeperSnapshot();
                    require(snapshot.creepers().size() == 1 && snapshot.creepers().getFirst().preparing(), "prepared creeper appears in warning snapshot");
                    require(snapshot.creepers().getFirst().radius() == 8, "NBT ExplosionRadius=4 produces conservative radius8 warning");
                    preparedAt = ticks;
                    stage = 3;
                    Tactical.LOGGER.info("[Tactical] CREEPER fixture holding player turn for 220 real ticks at {}", cell);
                } else if (encounter.isPlayerTurnNow()) {
                    encounter.endPlayerTurn();
                }
            } else if (stage == 3) {
                require(creeper.isAlive() && !creeper.isRemoved() && explosions == 0, "prepared creeper survives real-time fuse duration");
                require(access(creeper).tactical$isPreparing() && creeper.getSwelling(0.5F) == 0.75F, "warning pose remains stable while waiting");
                if (ticks - preparedAt < 220) return;
                require(encounter.isPlayerTurnNow(), "wall time alone never advances player turn");
                allowDetonation = true;
                encounter.endPlayerTurn();
                stage = 4;
            } else if (stage == 4) {
                if (!creeper.isRemoved()) {
                    if (encounter.isPlayerTurnNow()) encounter.endPlayerTurn();
                    return;
                }
                require(explosions == 1, "next creeper own turn invokes exactly one vanilla explosion");
                require(!witness.isAlive(), "vanilla explosion kills nearby participant through combat hooks");
                require(!access(creeper).tactical$isControlled() && !access(creeper).tactical$isPreparing(), "detonated participant lifecycle cleaned up");
                require(encounter.finished() && !encounter.creeperSnapshot().active()
                        && encounter.creeperSnapshot().creepers().isEmpty(), "last enemy explosion ends battle and clears warnings");
                runSnapshotAndCleanupChecks();
                Tactical.LOGGER.info("[Tactical] CREEPER SELFTEST PASSED: actual approach, stand-off, ignition/fuse freeze220ticks, next-own-turn vanilla explosion, damage/mobGriefing hooks, NBT/powered geometry, multi-creeper codec snapshot, death/end/rejoin cleanup, generic fallback");
                cleanup();
                event.getServer().halt(false);
            }
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] CREEPER SELFTEST FAILED at stage {} tick {}", stage, ticks, failure);
            cleanup();
            event.getServer().halt(false);
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int floor = Math.min(level.getMaxBuildHeight() - 8, spawn.getY() + 24);
        origin = new GridPos(spawn.getX(), floor + 1, spawn.getZ());
        oldMobGriefing = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, server);
        for (int cx = (origin.x() - 12) >> 4; cx <= (origin.x() + 12) >> 4; cx++) {
            for (int cz = (origin.z() - 12) >> 4; cz <= (origin.z() + 12) >> 4; cz++) {
                if (!level.getForcedChunks().contains(ChunkPos.asLong(cx, cz))) {
                    level.setChunkForced(cx, cz, true);
                    forced.add(new ChunkPos(cx, cz));
                }
            }
        }
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                level.setBlock(new BlockPos(origin.x() + dx, floor, origin.z() + dz), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 1; dy <= 4; dy++) {
                    level.setBlock(new BlockPos(origin.x() + dx, floor + dy, origin.z() + dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        player = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes(
                "tactical-creeper-test".getBytes(StandardCharsets.UTF_8)), "CreeperTest"));
        player.setPos(origin.x() + 0.5, origin.y(), origin.z() + 0.5);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(4);
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.2);
        player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1024);
        player.setHealth(1024);
        creeper = spawnCreeper(8, 4, false);
        witness = EntityType.ZOMBIE.create(level);
        require(witness != null, "damage witness created");
        witness.setNoAi(true);
        witness.setHealth(1);
        witness.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_HELMET));
        witness.setPos(origin.x() + 4.5, origin.y(), origin.z() + 2.5);
        require(level.addFreshEntity(witness), "damage witness spawned");
        fixtures.add(witness);
    }

    private static Creeper spawnCreeper(int dx, int radius, boolean powered) {
        Creeper mob = EntityType.CREEPER.create(level);
        require(mob != null, "creeper fixture created");
        CompoundTag data = new CompoundTag();
        data.putShort("Fuse", (short) 5);
        data.putByte("ExplosionRadius", (byte) radius);
        data.putBoolean("powered", powered);
        mob.readAdditionalSaveData(data);
        mob.setPos(origin.x() + dx + 0.5, origin.y(), origin.z() + 0.5);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        require(level.addFreshEntity(mob), "creeper fixture spawned");
        fixtures.add(mob);
        return mob;
    }

    private static void runSnapshotAndCleanupChecks() {
        Creeper normal = spawnCreeper(2, 3, false);
        Creeper powered = spawnCreeper(-2, 5, true);
        BattleAiSession session = new BattleAiSession(player);
        try {
            session.add(normal);
            session.add(powered);
            AiContext first = context(normal);
            AiContext second = context(powered);
            session.resolveInstant(first, session.planTurn(first));
            session.resolveInstant(second, session.planTurn(second));
            CreeperTelegraphPacket snapshot = session.snapshot();
            require(snapshot.creepers().size() == 2 && snapshot.creepers().stream().allMatch(CreeperTelegraphPacket.CreeperWarning::preparing),
                    "snapshot contains ALL simultaneously prepared creepers");
            require(snapshot.creepers().stream().anyMatch(warning -> warning.uuid().equals(normal.getUUID()) && warning.radius() == 6), "normal warning radius6");
            require(snapshot.creepers().stream().anyMatch(warning -> warning.uuid().equals(powered.getUUID()) && warning.radius() == 20), "charged NBT radius5 warning20");
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                CreeperTelegraphPacket.STREAM_CODEC.encode(buffer, snapshot);
                require(CreeperTelegraphPacket.STREAM_CODEC.decode(buffer).equals(snapshot), "full warning packet codec round trip");
            } finally {
                buffer.release();
            }
            normal.setHealth(0);
            require(session.snapshot().creepers().size() == 1, "dead prepared creeper immediately excluded from snapshot");
            session.remove(normal);
            require(!access(normal).tactical$isControlled() && !access(normal).tactical$isPreparing(), "death clears tactical entity state");
            session.clear();
            require(!session.snapshot().active() && session.snapshot().creepers().isEmpty(), "battle end snapshot clears all warnings");
            require(!access(powered).tactical$isControlled() && !access(powered).tactical$isPreparing(), "battle end releases surviving creeper");
            BattleAiSession nextBattle = new BattleAiSession(player);
            try {
                nextBattle.add(powered);
                require(!nextBattle.battleId().equals(session.battleId()) && !nextBattle.snapshot().creepers().getFirst().preparing(), "joining a new battle has fresh identity and lifecycle");
                require(((CreeperTacticalAi) nextBattle.adapter(powered)).state() == CreeperTacticalAi.State.APPROACHING, "adapter preparation does not leak across battles");
            } finally {
                nextBattle.clear();
            }
            var zombie = EntityType.ZOMBIE.create(level);
            require(zombie != null && TacticalAiRegistry.create(zombie) instanceof GenericMeleeAi, "unregistered mob uses generic melee adapter");
        } finally {
            session.clear();
        }
    }

    private static AiContext context(Creeper actor) {
        return new AiContext() {
            public Mob actor() { return actor; }
            public GridPos actorCell() { return new GridPos(actor.getBlockX(), actor.getBlockY(), actor.getBlockZ()); }
            public GridPos playerCell() { return origin; }
            public int ap() { return 100; }
            public int moveCost() { return 5; }
            public List<GridPos> pathToPlayer() { return List.of(); }
            public List<GridPos> standableNeighbors(GridPos from) { return List.of(); }
            public boolean occupied(GridPos cell) { return cell.equals(origin); }
            public boolean inMeleeRange(GridPos from, GridPos target) { return GridMovementRules.canStep(from, target); }
            public boolean hasLineOfSight(GridPos from, GridPos target) { return actor.hasLineOfSight(player); }
            public void attackPlayer() { throw new AssertionError("creeper must not melee"); }
            public void log(String message) { Tactical.LOGGER.info("[Tactical] CREEPER fixture: {}", message); }
        };
    }

    private static TacticalCreeperAccess access(Creeper actor) { return (TacticalCreeperAccess) actor; }

    private static void cleanup() {
        enabled = false;
        if (player != null) BattleManager.stop(player);
        for (Mob mob : fixtures) mob.discard();
        fixtures.clear();
        if (level != null) {
            level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(oldMobGriefing, level.getServer());
            for (ChunkPos chunk : forced) level.setChunkForced(chunk.x, chunk.z, false);
        }
        forced.clear();
        player = null;
        creeper = null;
        witness = null;
        encounter = null;
        level = null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
