package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.neoforge.Tactical;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Real dedicated-server/native-hurt fixture. Opt in with -Dtactical.selftest=position. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class PositionDamageSelfTest {
    private static final List<Entity> entities = new ArrayList<>();
    private static final List<RecordingConnection> wires = new ArrayList<>();
    private static final List<BlockPos> chunks = new ArrayList<>();
    private static boolean enabled;
    private static int ticks, assertions, postHits;
    private static float postDamage;
    private static LivingEntity observed;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner, outsider;
    private static Creeper enemy;

    private PositionDamageSelfTest() {}

    @SubscribeEvent public static void onStarted(ServerStartedEvent event) {
        enabled = "position".equals(System.getProperty("tactical.selftest"));
        ticks = assertions = postHits = 0;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent.Post event) {
        if (!enabled || event.getEntity() != observed) return;
        postHits++;
        postDamage = event.getNewDamage();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        try {
            if (ticks == 20) setup(event.getServer());
            if (ticks < 100) return;
            check(ticks < 250, "loaded entities become tickable before deadline");
            if (!level.getEntitiesOfClass(Creeper.class, owner.getBoundingBox().inflate(16)).contains(enemy)) return;
            run();
            Tactical.LOGGER.info("[Tactical] POSITION SELFTEST PASSED: {} assertions, native hurt float/health, actual yaw sectors+height clamps, native arrows launch snapshot/incoming velocity, exclusions, defense, extension, real-player multiplayer isolation", assertions);
            finish(event.getServer());
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] POSITION SELFTEST FAILED at tick {}", ticks, failure);
            finish(event.getServer());
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    private static void setup(MinecraftServer server) {
        BattleManager.reset();
        BattleManager.setAutoTrigger(false);
        level = server.overworld();
        server.setDifficulty(Difficulty.NORMAL, true);
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX(), Math.min(level.getMaxBuildHeight() - 24, spawn.getY() + 16) + 1, spawn.getZ());
        for (int cx = (origin.getX() - 12) >> 4; cx <= (origin.getX() + 12) >> 4; cx++) {
            for (int cz = (origin.getZ() - 12) >> 4; cz <= (origin.getZ() + 12) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) chunks.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
            level.setBlock(origin.offset(x, -2, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = -1; y < 8; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        owner = player(server, "PositionOwner", 0);
        outsider = player(server, "PositionOther", -6);
        enemy = EntityType.CREEPER.create(level);
        check(enemy != null, "real unarmored mob created");
        enemy.setPos(origin.getX() + 3.5, origin.getY(), origin.getZ() + .5);
        enemy.setPersistenceRequired();
        enemy.setNoAi(true);
        enemy.setNoGravity(true);
        prepare(enemy);
        check(level.addFreshEntity(enemy), "real mob added to server level");
        entities.add(enemy);
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
        player.setNoGravity(true);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.5);
        prepare(player);
        level.addNewPlayer(player);
        entities.add(player);
        return player;
    }

    private static void run() {
        check(level.players().contains(owner) && level.players().contains(outsider), "two real server players coexist");
        check(enemy.getArmorValue() == 0 && owner.getArmorValue() == 0, "fixture has no armor");
        pose(owner, enemy, 0, 3, 3);
        hit(enemy, owner.damageSources().playerAttack(owner), 10, "outside battle keeps vanilla damage");
        start();
        directCases();
        arrowCases();
        extensionCases();
        isolationCases();
        defenseCases(false);
        defenseCases(true);
        BattleManager.reset();
        pose(owner, enemy, 0, -3, 3);
        hit(enemy, owner.damageSources().playerAttack(owner), 10, "finished encounter no longer modifies damage");
    }

    private static void start() {
        BattleManager.reset();
        owner.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        owner.getAttribute(TacticalAttributes.TURN_CHARGE_SPEED).setBaseValue(1);
        enemy.getAttribute(TacticalAttributes.TURN_CHARGE_SPEED).setBaseValue(1);
        owner.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
        enemy.setPos(origin.getX() + 3.5, origin.getY(), origin.getZ() + .5);
        prepare(owner);
        prepare(enemy);
        enemy.setTarget(owner);
        check(BattleManager.start(owner, true), "encounter starts with real owner and loaded mob");
        check(BattleManager.current().isParticipant(owner) && BattleManager.current().isParticipant(enemy)
                && !BattleManager.current().isParticipant(outsider), "owner-only encounter participant identity");
        check(BattleManager.current().isPlayerTurnNow(), "high attack-speed owner acts first");
        check(wires.get(0).payloads > 0, "actual packet listener reaches recording connection");
    }

    private static void directCases() {
        pose(owner, enemy, 0, 3, 0);
        hit(enemy, owner.damageSources().playerAttack(owner), 10, "front z+ yaw0");
        pose(owner, enemy, 3, 0, 0);
        hit(enemy, owner.damageSources().playerAttack(owner), 12, "side x+ yaw0");
        pose(owner, enemy, 0, -3, 0);
        hit(enemy, owner.damageSources().playerAttack(owner), 15, "rear z- yaw0");
        pose(owner, enemy, 0, 3, 1);
        hit(enemy, owner.damageSources().playerAttack(owner), 10.5F, "one-block higher is 1.05");
        pose(owner, enemy, 0, 3, -1);
        hit(enemy, owner.damageSources().playerAttack(owner), 9.5F, "one-block lower is .95");
        pose(owner, enemy, 0, 3, 20);
        hit(enemy, owner.damageSources().playerAttack(owner), 11.5F, "height advantage clamps at 15 percent");
        pose(owner, enemy, 0, -3, -20);
        hit(enemy, owner.damageSources().playerAttack(owner), 12.75F, "rear and minimum height multiply");
        pose(owner, enemy, 0, -3, 3);
        hit(enemy, owner.damageSources().playerAttack(owner), 17.25F, "rear and maximum height multiply");
        for (ResourceKey<DamageType> type : List.of(DamageTypes.MAGIC, DamageTypes.EXPLOSION,
                DamageTypes.ON_FIRE, DamageTypes.FALL, DamageTypes.INDIRECT_MAGIC, DamageTypes.THORNS)) {
            hit(enemy, source(type, owner, owner), 10, "excluded source " + type.location());
        }
        ThrownTrident trident = new ThrownTrident(EntityType.TRIDENT, level);
        trident.setOwner(owner);
        hit(enemy, source(DamageTypes.TRIDENT, trident, owner), 10, "trident excluded despite AbstractArrow superclass");
        trident.discard();
        Arrow effectCarrier=new Arrow(EntityType.ARROW,level);
        effectCarrier.setOwner(owner);
        effectCarrier.setPos(enemy.getX(),enemy.getY()+.8,enemy.getZ()-3);
        effectCarrier.setDeltaMovement(0,0,1);
        check(level.addFreshEntity(effectCarrier),"real arrow effect carrier added at rear/high launch");
        entities.add(effectCarrier);
        for(ResourceKey<DamageType> type:List.of(DamageTypes.MAGIC,DamageTypes.INDIRECT_MAGIC,DamageTypes.EXPLOSION)){
            hit(enemy,source(type,effectCarrier,owner),10,"arrow-carried nonphysical source excluded "+type.location());
        }
        effectCarrier.discard();
        pose(enemy, owner, 0, -3, 0);
        hit(owner, owner.damageSources().mobAttack(enemy), 15, "native mob_attack rear");
        hit(owner, source(DamageTypes.MOB_ATTACK_NO_AGGRO, enemy, enemy), 15, "native mob_attack_no_aggro rear");
    }

    private static void arrowCases() {
        nativeArrow(3, -3, 11.5F, "arrow retains high launch feet after shooter relocated low");
        nativeArrow(0, 0, 10, "incoming arrow front overrides shooter behind target");
    }

    private static void nativeArrow(int launchHeight, int movedHeight, float expected, String label) {
        prepare(enemy);
        enemy.setPos(origin.getX() + 3.5, origin.getY(), origin.getZ() + .5);
        enemy.setYRot(0);
        enemy.setYHeadRot(0);
        enemy.setYBodyRot(0);
        owner.setPos(enemy.getX(), enemy.getY() + launchHeight, enemy.getZ() - 3);
        Arrow arrow = new Arrow(EntityType.ARROW, level);
        arrow.setOwner(owner);
        arrow.setNoGravity(true);
        arrow.setCritArrow(false);
        arrow.setBaseDamage(8);
        // 3:4:5 geometry gives exact speed 1.25 and native ceil damage 10.
        // The high shot really spawns high and flies down to the target.
        arrow.setPos(enemy.getX(), enemy.getY() + .8 + launchHeight, enemy.getZ() + 4);
        arrow.shoot(0, -launchHeight, -4, 1.25F, 0);
        check(level.addFreshEntity(arrow), "native arrow join records launch geometry");
        entities.add(arrow);
        owner.setPos(enemy.getX(), enemy.getY() + movedHeight, enemy.getZ() - 3);
        observed = enemy;
        postHits = 0;
        float before = enemy.getHealth();
        for (int step = 0; step < 8 && postHits == 0 && !arrow.isRemoved(); step++) arrow.tick();
        close(before - enemy.getHealth(), expected, label + " health");
        check(postHits == 1, label + " exactly one native damage post");
        close(postDamage, expected, label + " event float");
        observed = null;
        arrow.discard();
    }

    private static void extensionCases() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "position_selftest_backstab");
        int[] invocations = {0};
        TacticalDamageMultipliers.register(id, (context, event) -> {
            if (context.attacker() != owner || context.target() != enemy) return;
            invocations[0]++;
            if (event.base().direction() == 1.5D) {
                double direction = event.breakdown().direction();
                double height = event.breakdown().height();
                event.multiply(TacticalDamageMultipliers.Stage.SKILL, 2);
                check(event.breakdown().direction() == direction && event.breakdown().height() == height,
                        "registered skill leaves direction and height buckets intact");
                check(event.breakdown().skill() == 2, "registered backstab uses independent skill bucket");
            }
        });
        try {
            pose(owner, enemy, 0, -3, 3);
            hit(enemy, owner.damageSources().playerAttack(owner), 34.5F, "public rear skill doubles stacked 17.25");
            pose(owner, enemy, 0, 3, 3);
            hit(enemy, owner.damageSources().playerAttack(owner), 11.5F, "backstab skill leaves front attack unchanged");
            check(invocations[0] == 2, "one public modifier invocation per native eligible hit");
        } finally {
            check(TacticalDamageMultipliers.unregister(id), "fixture unregisters only its own public modifier");
        }
        pose(owner, enemy, 0, -3, 3);
        hit(enemy, owner.damageSources().playerAttack(owner), 17.25F, "unregistered skill leaves base factors intact");
    }

    private static void isolationCases() {
        pose(owner, enemy, 0, -3, 0);
        hit(outsider, owner.damageSources().playerAttack(owner), 0, "owner cannot hurt outsider");
        hit(enemy, owner.damageSources().playerAttack(outsider), 0, "outsider cannot hurt participant");
        check(BattleManager.current().player() == owner && !BattleManager.current().isParticipant(outsider),
                "outside multiplayer identity remains outside encounter");
    }

    private static void defenseCases(boolean shield) {
        start();
        if (shield) owner.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        // The owner starts fast, then yields to an enemy turn while the guard is active.
        // tryDefendFacing -> advanceTurn refreshes these real native attributes.
        owner.getAttribute(TacticalAttributes.TURN_CHARGE_SPEED).setBaseValue(.01);
        enemy.getAttribute(TacticalAttributes.TURN_CHARGE_SPEED).setBaseValue(100);
        BattleManager.current().tryDefendFacing(0);
        check(!BattleManager.current().isPlayerTurnNow(), "defense yields to enemy before the next owner turn");
        check(BattleManager.current().isDefending(), "public defend action activates guard");
        float factor = shield ? .3F : .5F;
        pose(enemy, owner, 0, -3, 0);
        hit(owner, owner.damageSources().mobAttack(enemy), 15 * factor, "rear multiplies independent defense shield=" + shield);
        enemy.setPos(owner.getX(), owner.getY() + 3, owner.getZ() - 3);
        for (ResourceKey<DamageType> type : List.of(DamageTypes.MAGIC, DamageTypes.EXPLOSION,
                DamageTypes.ON_FIRE, DamageTypes.FALL, DamageTypes.INDIRECT_MAGIC, DamageTypes.THORNS)) {
            hit(owner, source(type, enemy, enemy), 10 * factor, "excluded source retains existing defense " + type.location() + " shield=" + shield);
        }
    }

    private static DamageSource source(ResourceKey<DamageType> type, Entity direct, Entity attacker) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type), direct, attacker);
    }

    private static void pose(LivingEntity attacker, LivingEntity target, double x, double z, double height) {
        target.setPos(origin.getX() + 3.5, origin.getY(), origin.getZ() + .5);
        target.setYRot(0);
        target.setYHeadRot(0);
        target.setYBodyRot(0);
        attacker.setPos(target.getX() + x, target.getY() + height, target.getZ() + z);
        attacker.setDeltaMovement(Vec3.ZERO);
        target.setDeltaMovement(Vec3.ZERO);
    }

    private static void prepare(LivingEntity target) {
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        target.getAttribute(Attributes.ARMOR).setBaseValue(0);
        target.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(0);
        target.removeAllEffects();
        target.setHealth(200);
        target.setAbsorptionAmount(0);
        target.invulnerableTime = 0;
        target.hurtTime = 0;
        target.clearFire();
        if (target instanceof ServerPlayer player) {
            player.resetAttackStrengthTicker();
            player.setInvulnerable(false);
        }
    }

    private static void hit(LivingEntity target, DamageSource source, float expected, String label) {
        prepare(target);
        owner.resetAttackStrengthTicker();
        observed = target;
        postHits = 0;
        postDamage = Float.NaN;
        float before = target.getHealth();
        boolean accepted = target.hurt(source, 10);
        close(before - target.getHealth(), expected, label + " health");
        if (expected > 0) {
            check(accepted && postHits == 1, label + " uses successful native hurt and damage post");
            close(postDamage, expected, label + " event float");
        } else check(!accepted && postHits == 0, label + " cancelled before damage post");
        observed = null;
    }

    private static void close(float actual, float expected, String label) {
        check(Math.abs(actual - expected) <= .002F, label + ": expected=" + expected + " actual=" + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        observed = null;
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

    private static final class RecordingConnection extends Connection {
        private EmbeddedChannel channel;
        private int payloads;
        private RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundCustomPayloadPacket) payloads++;
        }
    }
}
