package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.BattleStatePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Opt-in dedicated-server fixture: -Dtactical.selftest=regeneration.
 * Real ServerPlayers, real manager ownership and real server connection ticks.
 * No direct MobEffectInstance.tick/FoodData.tick calls or simulated tick loops.
 * Both sources wait 120 real server ticks before and between complete own cycles.
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class RegenerationSelfTest {
    private static final int HOLD_TICKS = 120;
    private static final float START_HEALTH = 10;
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<RecordingConnection> connections = new ArrayList<>();
    private static final List<BlockPos> forcedChunks = new ArrayList<>();
    private static boolean enabled;
    private static int ticks;
    private static int phaseTick;
    private static int cycle;
    private static int heals;
    private static int sampleHeals;
    private static int sampleConnectionTicks;
    private static float sampleHealth;
    private static int sampleFood;
    private static float sampleSaturation;
    private static float sampleExhaustion;
    private static Phase phase;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static ServerPlayer outsider;
    private static Creeper enemy;
    private static boolean originalNaturalRegeneration;
    private static Difficulty originalDifficulty;
    private static ServerBattle battle;

    private enum Phase { LOAD, OUTSIDE, FOOD_HOLD, FOOD_WAIT, POTION_HOLD, POTION_WAIT, CONSUME_HOLD, CONSUME_WAIT, STARVE_HOLD, STARVE_WAIT, RELEASE }

    private RegenerationSelfTest() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "regeneration".equals(System.getProperty("tactical.selftest"));
        ticks = 0;
        phase = Phase.LOAD;
        heals = 0;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHeal(LivingHealEvent event) {
        if (enabled && event.getEntity() == owner && event.getAmount() > 0 && !event.isCanceled()) heals++;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        try {
            check(ticks < 2400, "fixture completes before real-server tick deadline");
            if (ticks == 20) setup(event.getServer());
            if (ticks < 40) return;
            switch (phase) {
                case LOAD -> {
                    if (!level.getEntitiesOfClass(Creeper.class, owner.getBoundingBox().inflate(12)).contains(enemy)) return;
                    sample(Phase.OUTSIDE);
                }
                case OUTSIDE -> {
                    if (!elapsed()) return;
                    realTicks();
                    check(owner.getHealth() > sampleHealth && heals - sampleHeals > 1,
                            "positive control: real player natural food healing runs outside battle");
                    prepareFood(owner, 20, 5);
                    owner.setHealth(START_HEALTH);
                    startBattle();
                    owner.getFoodData().addExhaustion(5);
                    check(!owner.hasEffect(MobEffects.REGENERATION), "natural-food case has no potion");
                    cycle = 0;
                    sample(Phase.FOOD_HOLD);
                }
                case FOOD_HOLD -> {
                    if (!elapsed()) return;
                    unchanged("natural food while player holds own turn");
                    check(outsider.getHealth() > START_HEALTH, "nonparticipant still heals with gamerule true");
                    if (cycle == 0) check(close(owner.getFoodData().getSaturationLevel(), 5)
                            && close(owner.getFoodData().getExhaustionLevel(), 5), "wall-time hunger/exhaustion processing is frozen");
                    if (cycle == 2) {
                        // Direct and instantaneous healing must remain immediate in battle.
                        float before = owner.getHealth();
                        owner.heal(2);
                        check(close(owner.getHealth(), before + 2), "direct healing remains immediate");
                        before = owner.getHealth();
                        MobEffects.HEAL.value().applyInstantenousEffect(null, null, owner, 0, 1);
                        check(close(owner.getHealth(), before + 4), "instant health remains immediate");
                        BattleManager.stop(owner);
                        owner.setHealth(START_HEALTH);
                        prepareFood(owner, 17, 0);
                        owner.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 600, 3));
                        startBattle();
                        cycle = 0;
                        sample(Phase.POTION_HOLD);
                    } else {
                        if (cycle != 0) prepareFood(owner, 20, 5);
                        requestCycle(Phase.FOOD_WAIT);
                    }
                }
                case FOOD_WAIT -> {
                    if (!returnedToOwnTurn()) return;
                    float expectedHeal = cycle == 0 ? 4.0F / 6 : 5.0F / 6;
                    check(heals - sampleHeals == 1 && close(owner.getHealth(), sampleHealth + expectedHeal),
                            "one vanilla saturation-dependent food heal per complete own cycle");
                    check(close(owner.getFoodData().getExhaustionLevel(), 5)
                            && close(owner.getFoodData().getSaturationLevel(), cycle == 0 ? 4 : 5),
                            "one vanilla exhaustion consumption followed by one food-healing charge");
                    cycle++;
                    sample(Phase.FOOD_HOLD);
                }
                case POTION_HOLD -> {
                    if (!elapsed()) return;
                    unchanged("amplified potion regeneration while player holds own turn");
                    int expectedDuration = 600 - cycle * BattleEffects.TICKS_PER_OWN_CYCLE;
                    MobEffectInstance potion = owner.getEffect(MobEffects.REGENERATION);
                    check(expectedDuration == 0 ? potion == null : potion != null && potion.getDuration() == expectedDuration,
                            "real wall ticks preserve potion duration after each own start");
                    if (cycle == 3) {
                        BattleManager.stop(owner);
                        level.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(false, event.getServer());
                        owner.setHealth(owner.getMaxHealth());
                        prepareFood(owner, 19, 0);
                        owner.getFoodData().addExhaustion(5);
                        startBattle();
                        cycle = 0;
                        sample(Phase.CONSUME_HOLD);
                    } else requestCycle(Phase.POTION_WAIT);
                }
                case POTION_WAIT -> {
                    if (!returnedToOwnTurn()) return;
                    check(heals - sampleHeals == 1 && close(owner.getHealth(), sampleHealth + 1),
                            "amplified regeneration heals exactly once through actual owner-cycle transition");
                    cycle++;
                    sample(Phase.POTION_HOLD);
                }
                case CONSUME_HOLD -> {
                    if (!elapsed()) return;
                    unchanged("full-health food consumption with natural regeneration disabled");
                    requestCycle(Phase.CONSUME_WAIT);
                }
                case CONSUME_WAIT -> {
                    if (!returnedToOwnTurn()) return;
                    check(close(owner.getHealth(), sampleHealth) && heals == sampleHeals
                            && owner.getFoodData().getFoodLevel() == 18
                            && close(owner.getFoodData().getSaturationLevel(), 0)
                            && close(owner.getFoodData().getExhaustionLevel(), 1),
                            "full-health actor consumes exactly one food unit per cycle despite natural regeneration disabled");
                    prepareFood(owner, 0, 0);
                    owner.setHealth(START_HEALTH);
                    event.getServer().setDifficulty(Difficulty.HARD, true);
                    cycle = 0;
                    sample(Phase.STARVE_HOLD);
                }
                case STARVE_HOLD -> {
                    if (!elapsed()) return;
                    unchanged("starvation with natural regeneration disabled");
                    if (cycle == 2) {
                        BattleManager.stop(owner);
                        level.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(true, event.getServer());
                        event.getServer().setDifficulty(Difficulty.NORMAL, true);
                        prepareFood(owner, 20, 5);
                        sample(Phase.RELEASE);
                    } else {
                        owner.invulnerableTime = 19;
                        ((com.tactical.neoforge.mixin.BattleLivingEntityEffectsAccess) owner).tactical$lastHurt(7);
                        requestCycle(Phase.STARVE_WAIT);
                        check(owner.invulnerableTime == 19
                                && ((com.tactical.neoforge.mixin.BattleLivingEntityEffectsAccess) owner).tactical$lastHurt() == 7,
                                "food settlement restores pre-existing hurt cooldown state");
                    }
                }
                case STARVE_WAIT -> {
                    if (!returnedToOwnTurn()) return;
                    check(close(owner.getHealth(), sampleHealth - 1) && heals == sampleHeals
                            && owner.getFoodData().getFoodLevel() == 0,
                            "exactly one native starvation HP per complete own cycle, independent of natural regeneration gamerule");
                    cycle++;
                    sample(Phase.STARVE_HOLD);
                }
                case RELEASE -> {
                    if (!elapsed()) return;
                    realTicks();
                    check(!BattleManager.isParticipant(owner) && heals > sampleHeals && owner.getHealth() > sampleHealth,
                            "normal player food healing resumes after battle stop");
                    Tactical.LOGGER.info("[Tactical] REGENERATION SELFTEST PASSED: actual ServerPlayer connection ticks, 120-tick food/potion/exhaustion/starvation holds, one native food tick per own cycle, full-health consumption with natural regeneration disabled, one starvation pulse, hurt cooldown restored, outsider control, direct/instant heals, release");
                    finish(event.getServer());
                }
            }
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] REGENERATION SELFTEST FAILED at real tick {}, phase {}, cycle {}, hp {}, heals {}",
                    ticks, phase, cycle, owner == null ? -1 : owner.getHealth(), heals, failure);
            finish(event.getServer());
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    private static void sample(Phase next) {
        phase = next;
        phaseTick = ticks;
        sampleHealth = owner.getHealth();
        sampleHeals = heals;
        sampleConnectionTicks = connections.get(0).ticks;
        sampleFood = owner.getFoodData().getFoodLevel();
        sampleSaturation = owner.getFoodData().getSaturationLevel();
        sampleExhaustion = owner.getFoodData().getExhaustionLevel();
        Tactical.LOGGER.info("[Tactical] REGENERATION SAMPLE phase={} cycle={} worldTick={} hp={} healCalls={} playerTicks={} food={} saturation={} exhaustion={} potionDuration={}",
                next, cycle, ticks, sampleHealth, heals, sampleConnectionTicks, sampleFood, sampleSaturation, sampleExhaustion,
                owner.hasEffect(MobEffects.REGENERATION) ? owner.getEffect(MobEffects.REGENERATION).getDuration() : 0);
    }

    private static boolean elapsed() { return ticks - phaseTick >= HOLD_TICKS; }

    private static void realTicks() {
        check(connections.get(0).ticks - sampleConnectionTicks >= HOLD_TICKS,
                "at least 120 real server-driven player connection ticks elapsed");
    }

    private static void unchanged(String description) {
        realTicks();
        check(BattleManager.current() == battle && battle.isOwner(owner) && battle.isPlayerTurnNow(), "actual manager owner keeps same turn");
        check(close(owner.getHealth(), sampleHealth) && heals == sampleHeals,
                description + ": baselineHP=" + sampleHealth + ", observedHP=" + owner.getHealth() + ", heals=" + (heals - sampleHeals));
        check(owner.getFoodData().getFoodLevel() == sampleFood
                && close(owner.getFoodData().getSaturationLevel(), sampleSaturation)
                && close(owner.getFoodData().getExhaustionLevel(), sampleExhaustion),
                description + ": food, saturation and exhaustion stay frozen throughout real ticks");
    }

    private static void requestCycle(Phase next) {
        sample(next);
        BattleManager.endTurn(owner);
        // A high-initiative actor may legitimately receive consecutive own
        // turns. The real manager still advances and settles that complete cycle.
    }

    private static boolean returnedToOwnTurn() {
        check(BattleManager.current() == battle && !battle.finished(), "real encounter stays active");
        check(ticks - phaseTick < 200, "enemy phase completes within real tick deadline");
        return battle.isPlayerTurnNow();
    }

    private static void prepareFood(ServerPlayer player, int food, float saturation) {
        player.getFoodData().setFoodLevel(food);
        player.getFoodData().setSaturation(saturation);
        player.getFoodData().setExhaustion(0);
    }

    private static void startBattle() {
        enemy.setNoAi(false);
        check(BattleManager.start(owner, true), "actual manager starts owner battle");
        battle = BattleManager.current();
        check(battle != null && battle.isOwner(owner) && battle.isPlayerTurnNow() && BattleManager.isParticipant(owner)
                && BattleManager.isParticipant(enemy) && !BattleManager.isParticipant(outsider), "real owner/enemy participation excludes outsider");
        check(connections.get(0).activeStates > 0, "battle state crosses actual player packet listener");
    }

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        originalNaturalRegeneration = level.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
        originalDifficulty = level.getDifficulty();
        level.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(true, server);
        server.setDifficulty(Difficulty.NORMAL, true);
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX(), Math.min(level.getMaxBuildHeight() - 8, spawn.getY() + 17), spawn.getZ());
        for (int cx = (origin.getX() - 12) >> 4; cx <= (origin.getX() + 12) >> 4; cx++) {
            for (int cz = (origin.getZ() - 12) >> 4; cz <= (origin.getZ() + 12) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) forcedChunks.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                level.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 0; dy < 4; dy++) level.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        owner = player(server, "RegenOwner", 0);
        outsider = player(server, "RegenOutside", -3);
        enemy = EntityType.CREEPER.create(level);
        check(enemy != null, "real enemy created");
        BlockPos cage = origin.offset(8, 0, 0);
        // A distant solid cage makes actual enemy turns harmless without damage
        // cancellation, player immunity, or replacing the battle's own turn flow.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 0; dy < 3; dy++) level.setBlock(cage.offset(dx, dy, dz), Blocks.OBSIDIAN.defaultBlockState(), 3);
            }
        }
        enemy.setPos(cage.getX() + .5, cage.getY(), cage.getZ() + .5);
        enemy.setPersistenceRequired();
        enemy.setNoAi(true);
        check(level.addFreshEntity(enemy), "real enemy registered");
    }

    private static ServerPlayer player(MinecraftServer server, String name, int dx) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("tactical-regeneration-" + name).getBytes(StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        RecordingConnection transport = new RecordingConnection();
        transport.channel = new EmbeddedChannel(transport);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph", "move_choice_offer")) {
            ChannelAttributes.getOrCreateAdHocChannels(transport).add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(server, transport, player, CommonListenerCookie.createInitial(profile, false));
        transport.player = player;
        player.setPos(origin.getX() + dx + .5, origin.getY(), origin.getZ() + .5);
        player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(80);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.2);
        player.setHealth(START_HEALTH);
        prepareFood(player, 20, 5);
        level.addNewPlayer(player);
        players.add(player);
        connections.add(transport);
        // Crucial: level.addNewPlayer alone never calls Player.tick/FoodData.tick.
        // Let the actual server connection loop tick this installed real listener.
        server.getConnection().getConnections().add(transport);
        return player;
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        BattleManager.reset();
        if (enemy != null) enemy.discard();
        for (RecordingConnection connection : connections) {
            server.getConnection().getConnections().remove(connection);
            if (connection.channel != null) connection.channel.finishAndReleaseAll();
        }
        for (ServerPlayer player : players) if (!player.isRemoved()) level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        if (level != null) {
            for (BlockPos chunk : forcedChunks) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
            level.getGameRules().getRule(GameRules.RULE_NATURAL_REGENERATION).set(originalNaturalRegeneration, server);
            server.setDifficulty(originalDifficulty, true);
        }
        players.clear();
        connections.clear();
        forcedChunks.clear();
        BattleEffects.clear();
        server.halt(false);
    }

    private static boolean close(float actual, float expected) { return Math.abs(actual - expected) < .001F; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class RecordingConnection extends Connection {
        private EmbeddedChannel channel;
        private ServerPlayer player;
        private int ticks;
        private int activeStates;

        private RecordingConnection() { super(PacketFlow.SERVERBOUND); }

        @Override
        public void tick() {
            // Replace only the absent socket transport. The server schedules this
            // once per real tick; the installed listener executes real doTick().
            ticks++;
            player.connection.tick();
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundKeepAlivePacket keepAlive) {
                player.connection.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
            }
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof BattleStatePacket state && state.active()) activeStates++;
        }
    }
}
