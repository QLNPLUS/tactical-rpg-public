package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.neoforge.Tactical;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Opt-in dedicated-server fixture: -Dtactical.selftest=flee. No client or FakePlayer. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class FleeSelfTest {
    private static final List<Entity> entities = new ArrayList<>();
    private static final List<RecordingConnection> wires = new ArrayList<>();
    private static final List<BlockPos> chunks = new ArrayList<>();
    private static boolean enabled;
    private static int ticks, grantedAt, damageMode;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner, outsider;
    private static Zombie enemy, otherEnemy;
    private static Piglin brainMob;
    private static ZombifiedPiglin neutral;

    private FleeSelfTest() {}

    @SubscribeEvent public static void onStarted(ServerStartedEvent event) {
        enabled = "flee".equals(System.getProperty("tactical.selftest"));
        ticks = 0;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncoming(LivingIncomingDamageEvent event) {
        if (enabled && damageMode == 1 && event.getEntity() == enemy) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPre(LivingDamageEvent.Pre event) {
        if (enabled && damageMode == 2 && event.getEntity() == enemy) event.setNewDamage(0);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        try {
            if (ticks == 20) setup(event.getServer());
            if (ticks < 40) return;
            check(ticks < 400, "fixture completes before deadline");
            if (!level.getEntitiesOfClass(Zombie.class, owner.getBoundingBox().inflate(12)).contains(enemy)) return;
            if (grantedAt == 0) {
                initialChecks(event.getServer());
                BattleManager.grantFleeImmunity(owner);
                grantedAt = ticks;
                return;
            }
            int elapsed = ticks - grantedAt;
            if (elapsed < 200) {
                check(FleeProtection.has(owner), "real tick " + elapsed + " retains protection");
                enemy.setTarget(owner);
                check(enemy.getTarget() == null, "native setTarget attempt is cancelled on every protected tick");
                check(BattleManager.current() == null, "protected player does not auto-enter battle");
                return;
            }
            check(elapsed == 200 && !FleeProtection.has(owner), "expires on exactly the 200th real tick");
            finalChecks(event.getServer());
            Tactical.LOGGER.info("[Tactical] FLEE SELFTEST PASSED: 200 real ticks, actual successful flee+finish, immediate target/hurt/brain/anger cleanup, outsider isolation, native reacquisition cancellation, post-damage direct/projectile/absorption cancellation, cancelled/zero inert, recursive arrow/TNT/cloud/pet source identity, normal auto-battle on/off");
            finish(event.getServer());
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] FLEE SELFTEST FAILED at tick {}", ticks, failure);
            try {
                finish(event.getServer());
            } catch (Throwable ignored) {
                // A broken teardown must not hide the original failure.
            }
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    private static void setup(MinecraftServer server) {
        BattleManager.reset();
        BattleManager.setAutoTrigger(false);
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX(), Math.min(level.getMaxBuildHeight() - 8, spawn.getY() + 16) + 1, spawn.getZ());
        for (int cx = (origin.getX() - 12) >> 4; cx <= (origin.getX() + 12) >> 4; cx++) {
            for (int cz = (origin.getZ() - 12) >> 4; cz <= (origin.getZ() + 12) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) chunks.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
            level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 0; y < 4; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        owner = player(server, "FleeOwner", 0);
        outsider = player(server, "FleeOther", -4);
        enemy = spawn(EntityType.ZOMBIE, 3);
        otherEnemy = spawn(EntityType.ZOMBIE, -7);
        brainMob = spawn(EntityType.PIGLIN, 7);
        neutral = spawn(EntityType.ZOMBIFIED_PIGLIN, 9);
        owner.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        owner.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.5);
        otherEnemy.setTarget(outsider);
    }

    private static <T extends net.minecraft.world.entity.Mob> T spawn(EntityType<T> type, int dx) {
        T mob = type.create(level);
        check(mob != null, "actual mob created");
        mob.setPos(origin.getX() + dx + .5, origin.getY(), origin.getZ() + .5);
        mob.setPersistenceRequired();
        mob.setNoAi(true);
        check(level.addFreshEntity(mob), "mob registered");
        entities.add(mob);
        return mob;
    }

    private static ServerPlayer player(MinecraftServer server, String name, int dx) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        RecordingConnection wire = new RecordingConnection();
        wire.channel = new EmbeddedChannel(wire);
        wires.add(wire);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph", "move_choice_offer")) {
            ChannelAttributes.getOrCreateAdHocChannels(wire).add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(server, wire, player, CommonListenerCookie.createInitial(profile, false));
        player.setPos(origin.getX() + dx + .5, origin.getY(), origin.getZ() + .5);
        level.addNewPlayer(player);
        entities.add(player);
        return player;
    }

    private static void initialChecks(MinecraftServer server) {
        check(level.players().contains(owner) && level.players().contains(outsider), "two actual ServerPlayers coexist");
        enemy.setTarget(owner);
        enemy.setLastHurtByMob(owner);
        enemy.setLastHurtByPlayer(owner);
        enemy.setLastHurtMob(owner);
        brainMob.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(owner, 1, 1));
        brainMob.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(owner, true));
        brainMob.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, owner);
        brainMob.getBrain().setMemory(MemoryModuleType.HURT_BY_ENTITY, owner);
        brainMob.getBrain().setMemory(MemoryModuleType.HURT_BY, owner.damageSources().playerAttack(owner));
        brainMob.getBrain().setMemory(MemoryModuleType.ANGRY_AT, owner.getUUID());
        neutral.setPersistentAngerTarget(owner.getUUID());
        neutral.setRemainingPersistentAngerTime(600);
        BattleManager.grantFleeImmunity(owner);
        check(FleeProtection.has(owner) && BattleManager.isImmune(owner), "public compatibility API delegates");
        check(enemy.getTarget() == null && enemy.getLastHurtByMob() == null
                && enemy.getLastHurtMob() == null && enemy.getKillCredit() == null, "grant immediately clears target and hurt source");
        check(!brainMob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                && !brainMob.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                && !brainMob.getBrain().hasMemoryValue(MemoryModuleType.LOOK_TARGET)
                && !brainMob.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY_ENTITY)
                && !brainMob.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY)
                && !brainMob.getBrain().hasMemoryValue(MemoryModuleType.ANGRY_AT), "brain memories forget only protected attacker");
        check(neutral.getPersistentAngerTarget() == null && neutral.getRemainingPersistentAngerTime() == 0, "matching neutral anger forgotten");
        check(otherEnemy.getTarget() == outsider, "outsider target untouched");
        neutral.setTarget(outsider);
        neutral.setPersistentAngerTarget(outsider.getUUID());
        neutral.setRemainingPersistentAngerTime(600);
        FleeProtection.give(owner);
        check(neutral.getTarget() == outsider && neutral.getRemainingPersistentAngerTime() == 600, "other player's neutral anger untouched");
        damageMode = 1;
        hurt(owner.damageSources().playerAttack(owner), 3);
        check(FleeProtection.has(owner), "cancelled incoming hit retains protection");
        damageMode = 2;
        hurt(owner.damageSources().playerAttack(owner), 3);
        check(FleeProtection.has(owner), "zero final damage retains protection");
        damageMode = 0;
        double originalMaxAbsorption = enemy.getAttributeValue(Attributes.MAX_ABSORPTION);
        enemy.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(10);
        enemy.setAbsorptionAmount(10);
        check(enemy.getAbsorptionAmount() == 10, "fixture absorption hearts survive MAX_ABSORPTION clamp");
        hurt(owner.damageSources().playerAttack(owner), 1);
        check(enemy.getHealth() == enemy.getMaxHealth() && enemy.getAbsorptionAmount() < 10,
                "fully absorbed hit consumes actual absorption without health loss");
        check(!FleeProtection.has(owner) && enemy.getTarget() == owner,
                "positive absorption-heart damage removes protection and restores aggro");
        enemy.setAbsorptionAmount(0);
        enemy.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(originalMaxAbsorption);
        FleeProtection.give(owner);
        hurt(owner.damageSources().playerAttack(outsider), 1);
        check(FleeProtection.has(owner), "other player's positive damage does not cancel owner protection");
        check(!FleeProtection.has(outsider), "protection is never copied to outsider");
        hurt(owner.damageSources().playerAttack(owner), 1);
        check(!FleeProtection.has(owner) && enemy.getTarget() == owner, "direct positive post-damage removes protection and restores aggro");
        check(BattleManager.current() == null, "damage handler never forces a battle with auto disabled");
        sourceIdentity();
        actualFlee(server);
        BattleManager.setAutoTrigger(true);
        otherEnemy.setTarget(null);
        neutral.setTarget(null);
    }

    private static void sourceIdentity() {
        Arrow arrow = new Arrow(EntityType.ARROW, level);
        arrow.setOwner(owner);
        check(FleeProtection.causingPlayer(owner.damageSources().arrow(arrow, owner)) == owner, "vanilla causing entity identifies shooter");
        check(FleeProtection.causingPlayer(new DamageSource(owner.damageSources().generic().typeHolder(), arrow)) == owner, "direct projectile recursively resolves owner");
        Arrow nested = new Arrow(EntityType.ARROW, level);
        nested.setOwner(arrow);
        check(FleeProtection.causingPlayer(new DamageSource(owner.damageSources().generic().typeHolder(), nested)) == owner, "nested projectile resolves owner recursively");
        PrimedTnt tnt = new PrimedTnt(level, 0, 0, 0, owner);
        check(FleeProtection.causingPlayer(new DamageSource(owner.damageSources().generic().typeHolder(), tnt)) == owner, "TNT owner resolves");
        AreaEffectCloud cloud = new AreaEffectCloud(level, 0, 0, 0);
        cloud.setOwner(owner);
        check(FleeProtection.causingPlayer(new DamageSource(owner.damageSources().generic().typeHolder(), cloud)) == owner, "lingering potion owner resolves");
        Wolf pet = EntityType.WOLF.create(level);
        check(pet != null, "pet created");
        pet.setOwnerUUID(owner.getUUID());
        check(FleeProtection.causingPlayer(owner.damageSources().mobAttack(pet)) == owner, "tamed damage resolves actual owner");
        check(FleeProtection.causingPlayer(owner.damageSources().generic()) == null, "environmental damage has no invented owner");
        FleeProtection.give(owner);
        hurt(owner.damageSources().arrow(arrow, owner), 1);
        check(!FleeProtection.has(owner) && enemy.getTarget() == owner, "positive projectile damage cancels grace");
        arrow.setOwner(arrow);
        check(FleeProtection.causingPlayer(new DamageSource(owner.damageSources().generic().typeHolder(), arrow)) == null, "cyclic ownership terminates safely");
    }

    private static void actualFlee(MinecraftServer server) {
        otherEnemy.setTarget(null);
        neutral.setTarget(null);
        brainMob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        for (int attempt = 0; attempt < 30; attempt++) {
            FleeProtection.remove(owner);
            enemy.setTarget(owner);
            // This fixture keeps its mobs still with NoAi(true). The assertion below checks that the
            // *battle-imposed* AI lock is released on finish, so the lock must belong to the battle:
            // hand the mob over AI-live and let admit() take the lock.
            enemy.setNoAi(false);
            check(BattleManager.start(owner, true), "actual encounter starts");
            ServerBattle battle = BattleManager.current();
            check(battle.isPlayerTurnNow(), "high attack speed wins initiative");
            // Deterministic selection of a successful vanilla random draw, not a fake flee.
            level.random.setSeed(attempt);
            if (battle.tryFlee()) {
                check(battle.finished() && FleeProtection.has(owner), "successful flee gives grace and finishes encounter");
                check(enemy.getTarget() == null && !enemy.isNoAi(),
                        "finish does not resurrect target or retain AI lock: target=" + enemy.getTarget()
                                + " noAi=" + enemy.isNoAi() + " alive=" + enemy.isAlive()
                                + " participant=" + battle.isParticipant(enemy));
                BattleManager.onServerTick(new ServerTickEvent.Post(() -> true, server));
                check(BattleManager.current() == null, "finished flee leaves no active encounter");
                enemy.setNoAi(true);
                otherEnemy.setNoAi(true);
                brainMob.setNoAi(true);
                neutral.setNoAi(true);
                return;
            }
            BattleManager.reset();
        }
        throw new AssertionError("successful flee obtainable within deterministic seeds");
    }

    private static void finalChecks(MinecraftServer server) {
        BattleManager.setAutoTrigger(false);
        FleeProtection.give(owner);
        hurt(owner.damageSources().playerAttack(owner), 1);
        BattleManager.onServerTick(new ServerTickEvent.Post(() -> true, server));
        check(BattleManager.current() == null, "auto disabled stays disabled after cancellation");
        BattleManager.setAutoTrigger(true);
        for (int i = 0; i < 10 && BattleManager.current() == null; i++) BattleManager.onServerTick(new ServerTickEvent.Post(() -> true, server));
        check(BattleManager.current() != null && BattleManager.current().isOwner(owner), "normal hasAggro scanner naturally restarts after damage");
    }

    private static void hurt(DamageSource source, float amount) {
        enemy.invulnerableTime = 0;
        enemy.setHealth(enemy.getMaxHealth());
        enemy.hurt(source, amount);
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        damageMode = 0;
        BattleManager.reset();
        BattleManager.setAutoTrigger(true);
        for (Entity entity : entities) {
            if (entity instanceof ServerPlayer player && !player.isRemoved()) level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            else entity.discard();
        }
        for (RecordingConnection wire : wires) if (wire.channel != null) wire.channel.finishAndReleaseAll();
        if (level != null) for (BlockPos chunk : chunks) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        entities.clear();
        wires.clear();
        chunks.clear();
        server.halt(false);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class RecordingConnection extends Connection {
        private EmbeddedChannel channel;
        private RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {}
    }
}
