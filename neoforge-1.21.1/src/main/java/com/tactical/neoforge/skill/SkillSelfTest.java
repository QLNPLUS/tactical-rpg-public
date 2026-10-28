package com.tactical.neoforge.skill;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.battle.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.lang.reflect.Field;
import java.util.*;

/** Disposable native acceptance fixture. Opt in with -Dtactical.selftest=skills. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class SkillSelfTest {
    private static final ResourceLocation BACKSTAB = id("backstab");
    private static final ResourceLocation MAGIC = id("fixture_skill_magic");
    private static final ResourceLocation UNION = id("fixture_skill_union");
    private static final ResourceLocation LINE = id("fixture_skill_line");
    private static final ResourceLocation RECTANGLE = id("fixture_skill_rectangle");
    private static final ResourceLocation TRIANGLE = id("fixture_skill_triangle");
    private static final ResourceLocation INVALID = id("fixture_skill_nonfinite");
    private static final ResourceLocation EXTENSION = id("fixture_skill_extension");
    private static final List<Entity> entities = new ArrayList<>();
    private static final List<Wire> wires = new ArrayList<>();
    private static final List<BlockPos> chunks = new ArrayList<>();
    private static final Map<UUID, Integer> hits = new HashMap<>();
    private static final Map<UUID, Float> damage = new HashMap<>();
    private static boolean enabled, cancelBefore, cancelAttack, extensionEnabled, configured;
    private static int ticks, stage, stageAt, assertions, beforeEvents, nativeAttacks, actionCalls;
    private static int oldInitial, oldOutside, oldInterval, oldCap;
    private static ServerLevel world;
    private static BlockPos origin;
    private static ServerPlayer owner, outsider;
    private static Creeper enemy, second;
    private static Mob friend;
    private static ServerBattle battle;
    private static TacticalDamageMultipliers.Breakdown lastBreakdown;
    private static float bowHealth;
    private static double bowMp;

    private SkillSelfTest() {}
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, path); }

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        enabled = "skills".equals(System.getProperty("tactical.selftest"));
        ticks = stage = stageAt = assertions = beforeEvents = nativeAttacks = actionCalls = 0;
        cancelBefore = cancelAttack = extensionEnabled = configured = false;
    }

    @SubscribeEvent public static void before(SkillCastEvent.Before event) {
        if (!enabled || event.context().caster() != owner) return;
        beforeEvents++;
        if (cancelBefore) event.setCanceled(true);
    }

    @SubscribeEvent public static void attack(AttackEntityEvent event) {
        if (!enabled || event.getEntity() != owner) return;
        nativeAttacks++;
        if (cancelAttack) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void damaged(LivingDamageEvent.Post event) {
        if (!enabled || event.getSource().getEntity() != owner) return;
        hits.merge(event.getEntity().getUUID(), 1, Integer::sum);
        damage.merge(event.getEntity().getUUID(), event.getNewDamage(), Float::sum);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void multiplier(TacticalDamageEvent event) {
        if (enabled && event.context().attacker() == owner) lastBreakdown = event.breakdown();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) {
        if (!enabled) return;
        try {
            check(++ticks < 800, "fixture deadline");
            if (ticks == 20) { setup(event.getServer()); stage = 1; stageAt = ticks; return; }
            if (stage == 1) {
                if (ManaResources.current(owner) == 0) return;
                close(ManaResources.current(owner), 20, "outside interval restores exactly20");
                check(ticks - stageAt >= 200 && ticks - stageAt <= 220, "outside recovery waits actual200 ticks");
                ManaResources.set(owner, 95); stage = 2; stageAt = ticks;
            } else if (stage == 2) {
                if (ManaResources.current(owner) == 95) return;
                close(ManaResources.current(owner), 100, "outside recovery caps at actual maxMP100");
                check(ticks - stageAt >= 200 && ticks - stageAt <= 220, "second outside interval uses native clock");
                ManaResources.set(owner, 37);
                runNativeCases();
                launchBow(); stage = 3; stageAt = ticks;
            } else if (stage == 3) {
                if (hits.getOrDefault(second.getUUID(), 0) == 0 || battle.isBusy()) {
                    check(ticks - stageAt < 100, "real native bow resolves before deadline"); return;
                }
                check(second.getHealth() < bowHealth, "ordinary native bow still damages a hostile");
                close(ManaResources.current(owner), bowMp, "ordinary bow consumes no MP");
                check(lastBreakdown != null && lastBreakdown.skill() == 1, "active skill scope never leaks to later native bow");
                lifecycle();
                Tactical.LOGGER.info("[Tactical] SKILLS SELFTEST PASSED: {} assertions; real MP ticks/persistence, native rear melee+SKILL, resources/refunds/CD/gear, native grants/enchantments, target/LOS/formula preflight, shaped dedup, magic/effects, extension APIs and ordinary native bow", assertions);
                finish(event.getServer());
            }
        } catch (Exception | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] SKILLS SELFTEST FAILED stage={} tick={} assertions={} AP={} MP={} paid={} cooldown={} hits={} damage={}",
                stage, ticks, assertions, battle == null ? -1 : battle.actorAp(owner), owner == null ? -1 : ManaResources.current(owner),
                battle == null ? -1 : battle.successfulPaidActions(), battle == null ? -1 : TacticalSkills.cooldown(battle, owner, BACKSTAB), hits, damage, failure);
            try {
                finish(event.getServer());
            } catch (Throwable ignored) {
                // A broken teardown must not hide the original failure.
            }
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw new IllegalStateException("Native skills fixture failed", failure);
        }
    }

    private static void setup(MinecraftServer server) {
        BattleManager.reset(); BattleManager.setAutoTrigger(false);
        var config = TacticalConfig.INSTANCE;
        oldInitial = config.initialMp.get(); oldOutside = config.outsideMpRecovery.get();
        oldInterval = config.outsideMpInterval.get(); oldCap = config.participantCap.get(); configured = true;
        config.initialMp.set(100); config.outsideMpRecovery.set(20); config.outsideMpInterval.set(200); config.participantCap.set(8);
        world = server.overworld(); server.setDifficulty(Difficulty.NORMAL, true);
        BlockPos spawn = world.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX(), Math.min(world.getMaxBuildHeight() - 12, spawn.getY() + 20), spawn.getZ());
        for (int x = (origin.getX() - 16) >> 4; x <= (origin.getX() + 16) >> 4; x++)
            for (int z = (origin.getZ() - 16) >> 4; z <= (origin.getZ() + 16) >> 4; z++)
                if (world.setChunkForced(x, z, true)) chunks.add(new BlockPos(x, 0, z));
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
            world.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 0; y < 7; y++) world.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        owner = player(server, "SkillOwner", 0); outsider = player(server, "SkillOther", -8);
        check(!owner.getPersistentData().contains(ManaResources.DATA_KEY), "new player starts without a resource record");
        close(ManaResources.maximum(owner), 100, "native max MP defaults100");
        close(ManaResources.recovery(owner), 0, "native own-turn recovery defaults0");
        close(ManaResources.current(owner), 100, "first read initializes actual persistent MP once");
        outsider.getAttribute(ManaAttributes.MAX_MP).setBaseValue(60);
        close(ManaResources.current(outsider), 60, "initial MP respects a lower native maximum");
        outsider.getAttribute(ManaAttributes.MAX_MP).setBaseValue(100);
        close(ManaResources.current(outsider), 60, "raising maximum does not reinitialize saved MP");
        ManaResources.set(owner, 0);
        enemy = mob(EntityType.CREEPER, 0, 1); second = mob(EntityType.CREEPER, 4, 2);
        friend = mob(EntityType.COW, -4, 3);
        definitions();
    }

    private static ServerPlayer player(MinecraftServer server, String name, int dx) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, world, profile, ClientInformation.createDefault());
        Wire wire = new Wire(); wire.channel = new EmbeddedChannel(wire); wires.add(wire);
        // Deliberately old channels only: skill/network integrations must tolerate clients without new channels.
        for (String path : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph", "move_choice_offer"))
            ChannelAttributes.getOrCreateAdHocChannels(wire).add(id(path));
        new ServerGamePacketListenerImpl(server, wire, player, CommonListenerCookie.createInitial(profile, false));
        player.setPos(origin.getX() + dx + .5, origin.getY(), origin.getZ() + .5);
        player.setNoGravity(true); player.setInvulnerable(true);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.getAttribute(TacticalAttributes.TURN_CHARGE_SPEED).setBaseValue(100);
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.5);
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        prepare(player); player.setInvulnerable(true);
        world.addNewPlayer(player); entities.add(player); return player;
    }

    private static <T extends Mob> T mob(EntityType<T> type, int x, int z) {
        T actor = type.create(world); check(actor != null, "native fixture mob created");
        actor.setPos(origin.getX() + x + .5, origin.getY(), origin.getZ() + z + .5);
        actor.setNoAi(true); actor.setNoGravity(true); actor.setPersistenceRequired(); prepare(actor);
        actor.getAttribute(TacticalAttributes.TURN_CHARGE_SPEED).setBaseValue(.1);
        check(world.addFreshEntity(actor), "native fixture mob joins loaded level"); entities.add(actor); return actor;
    }

    private static void definitions() {
        SkillBindings.register(id("fixture_skill_variable"), "fixture_bonus", (b, c, t, l) -> c == owner && extensionEnabled ? 4 : 0);
        SkillSources.register(id("fixture_skill_source"), (actor, grant) -> {
            if (extensionEnabled && actor == owner) grant.accept(EXTENSION, 2);
        });
        register(MAGIC, """
            {"range":"10","ap_cost":"4","mp_cost":"2","damage":{"mode":"formula","type":"minecraft:magic","amount":"4"},
             "effects":[{"id":"minecraft:slowness","level":"level+1","duration":"level+1","duration_unit":"turns"}]}
            """);
        register(UNION, """
            {"aim":"ground","anchor":"caster","area":true,"range":"10","ap_cost":"0","mp_cost":"0",
             "shape":{"type":"union","children":[{"type":"circle","radius":3},{"type":"rectangle","width":4,"length":4}]},
             "damage":{"mode":"formula","type":"minecraft:magic","amount":"3"}}
            """);
        shaped(LINE, "{\"type\":\"line\",\"width\":1,\"length\":3,\"offset_z\":1}");
        shaped(RECTANGLE, "{\"type\":\"rectangle\",\"width\":2,\"length\":3,\"offset_x\":1,\"yaw\":90}");
        shaped(TRIANGLE, "{\"type\":\"triangle\",\"size\":\"level+2\",\"offset_x\":-1}");
        SkillRegistry.register(SkillDefinition.builder(INVALID).costs("15", "10").cooldown("1")
            .damage(ResourceLocation.withDefaultNamespace("magic"), "1/(level-level)").build());
        JsonObject extension = json("{\"max_level\":5,\"range\":10,\"attributes\":{}}");
        extension.getAsJsonObject("attributes").addProperty("native_power", BuiltInRegistries.ATTRIBUTE.getKey(Attributes.ATTACK_DAMAGE.value()).toString());
        JsonObject targetAlias = new JsonObject(); targetAlias.addProperty("attribute", BuiltInRegistries.ATTRIBUTE.getKey(Attributes.ARMOR.value()).toString());
        targetAlias.addProperty("target", true); extension.getAsJsonObject("attributes").add("target_guard", targetAlias);
        SkillRegistry.register(SkillDefinition.builder(EXTENSION).json(extension).costs("0", "0")
            .damage(ResourceLocation.withDefaultNamespace("magic"), "fixture_bonus+native_power+target_guard")
            .action(context -> { check(context.caster() == owner && context.level() == 2 && context.affected().equals(List.of(enemy)),
                "public custom action receives authoritative caster, source level and deduplicated target"); actionCalls++; }).build());
    }
    private static void register(ResourceLocation id, String definition) { SkillRegistry.register(SkillDefinition.parse(id, json(definition))); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void shaped(ResourceLocation id, String shape) {
        SkillRegistry.register(SkillDefinition.builder(id).aim(SkillDefinition.Aim.GROUND).costs("0", "0")
            .shape(json(shape), SkillDefinition.Anchor.CASTER).range("10")
            .damage(ResourceLocation.withDefaultNamespace("magic"), "1").build());
    }

    private static void runNativeCases() throws Exception {
        check(world.players().contains(owner) && world.players().contains(outsider), "actual server players coexist");
        for (Mob actor : world.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(24)))
            if (!entities.contains(actor) && actor instanceof net.minecraft.world.entity.monster.Enemy) actor.discard();
        check(BattleManager.start(owner, true), "native encounter starts"); battle = BattleManager.current();
        check(battle.isActorTurn(owner) && battle.currentAp() == 100 && battle.currentMaxAp() == 150,
            "actual owner first turn AP100 with cap150");
        close(ManaResources.current(owner), 37, "battle entry preserves current MP instead of refilling");
        check(battle.isParticipant(enemy) && battle.isParticipant(second), "loaded native hostile roster");
        check(battle.tryAddFriendly(friend), "native party member recruited through public API");
        sourceCases(); rejectionCases(); meleeCases(); recoveryCases(); formulaAndAreaCases();
    }

    private static void sourceCases() {
        clearGrants(); persistent(BACKSTAB, 2);
        check(SkillSources.resolve(owner).get(BACKSTAB) == 2, "persistent player NBT source");
        owner.setItemSlot(EquipmentSlot.MAINHAND, granted(Items.IRON_SWORD, BACKSTAB, 3));
        check(SkillSources.resolve(owner).get(BACKSTAB) == 3, "equipped mainhand component wins by level");
        owner.setItemSlot(EquipmentSlot.OFFHAND, granted(Items.BOOK, BACKSTAB, 4));
        check(SkillSources.resolve(owner).get(BACKSTAB) == 4, "offhand merges highest rather than summing");
        owner.setItemSlot(EquipmentSlot.HEAD, granted(Items.LEATHER_HELMET, BACKSTAB, 9));
        check(SkillSources.resolve(owner).get(BACKSTAB) == 5, "equipped armor grant caps at definition max5");
        clearGrants();
        ItemStack enchanted = new ItemStack(Items.IRON_SWORD);
        var enchantment = world.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(ResourceKey.create(Registries.ENCHANTMENT, BACKSTAB));
        enchanted.enchant(enchantment, 4); owner.setItemSlot(EquipmentSlot.MAINHAND, enchanted);
        check(SkillSources.resolve(owner).get(BACKSTAB) == 4, "real dynamic-registry enchantment maps level4");
        owner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD)); owner.setItemSlot(EquipmentSlot.OFFHAND, enchanted);
        check(!SkillSources.resolve(owner).containsKey(BACKSTAB), "enchantment mapping respects mainhand-only slot");
        clearGrants(); owner.setItemSlot(EquipmentSlot.MAINHAND, granted(Items.IRON_SWORD, BACKSTAB, 1));
    }

    private static void rejectionCases() throws Exception {
        setYaw(enemy, 180); denied(BACKSTAB, SkillTarget.entity(enemy.getUUID()), "front cannot backstab");
        setYaw(enemy, 90); denied(BACKSTAB, SkillTarget.entity(enemy.getUUID()), "side cannot backstab"); setYaw(enemy, 0);
        denied(BACKSTAB, SkillTarget.entity(friend.getUUID()), "enemy-only skill rejects party member");
        denied(BACKSTAB, SkillTarget.entity(outsider.getUUID()), "outsider target rejected");
        begin(enemy); denied(BACKSTAB, SkillTarget.entity(enemy.getUUID()), "off-turn owner rejected"); begin(owner);
        BlockPos obstruction = enemy.blockPosition().above(); var previous = world.getBlockState(obstruction);
        world.setBlock(obstruction, Blocks.STONE.defaultBlockState(), 3);
        denied(BACKSTAB, SkillTarget.entity(enemy.getUUID()), "actual solid collider blocks cast"); world.setBlock(obstruction, previous, 3);
        owner.setItemSlot(EquipmentSlot.MAINHAND, granted(Items.BOW, BACKSTAB, 1));
        denied(BACKSTAB, SkillTarget.entity(enemy.getUUID()), "melee-only backstab rejects actual bow");
        owner.setItemSlot(EquipmentSlot.MAINHAND, granted(Items.IRON_SWORD, BACKSTAB, 1));
        ManaResources.set(owner, 0); denied(BACKSTAB, SkillTarget.entity(enemy.getUUID()), "depleted MP rejects"); ManaResources.set(owner, 37);
        cancelBefore = true; int callbacks = beforeEvents;
        denied(BACKSTAB, SkillTarget.entity(enemy.getUUID()), "Before cancellation reserves nothing"); cancelBefore = false;
        check(beforeEvents == callbacks + 1, "cancellable public Before event actually reached");
        cancelAttack = true; int attacks = nativeAttacks;
        denied(BACKSTAB, SkillTarget.entity(enemy.getUUID()), "cancelled actual native attack refunds AP MP count and cooldown"); cancelAttack = false;
        check(nativeAttacks == attacks + 1, "native AttackEntityEvent actually reached");
    }

    private static void meleeCases() throws Exception {
        prepare(enemy); capture(); int ap = battle.currentAp(); double mp = ManaResources.current(owner);
        battle.tryAttack(enemy.getUUID());
        check(hits.getOrDefault(enemy.getUUID(), 0) == 1 && damage.get(enemy.getUUID()) > 0, "plain action calls actual native player attack/hurt once");
        float ordinary = damage.get(enemy.getUUID());
        check(lastBreakdown != null && lastBreakdown.skill() == 1, "plain native melee has no active SKILL multiplier");
        close(lastBreakdown.direction(), 1.5, "rear direction remains independent");
        check(battle.currentAp() == ap - 10 && battle.successfulPaidActions() == 1, "plain melee commits its configured10 AP");
        close(ManaResources.current(owner), mp, "plain melee consumes no MP");
        begin(owner); prepare(enemy); capture(); ap = battle.currentAp();
        check(TacticalSkills.cast(battle, owner, BACKSTAB, SkillTarget.entity(enemy.getUUID())), "actual active backstab succeeds");
        check(hits.getOrDefault(enemy.getUUID(), 0) == 1, "active skill produces one native damage event");
        close(damage.get(enemy.getUUID()), ordinary * 1.25, "level1 skill multiplies actual native melee by1.25");
        close(lastBreakdown.skill(), 1.25, "active skill uses its independent SKILL bucket");
        close(lastBreakdown.direction(), 1.5, "active skill preserves rear direction bucket");
        close(lastBreakdown.height(), 1, "active skill preserves equal-height bucket");
        check(battle.currentAp() == ap - 15 && battle.successfulPaidActions() == 1, "first active skill pays15 AP and one shared paid count");
        close(ManaResources.current(owner), mp - 10, "active skill pays fixed10 MP");
        check(TacticalSkills.cooldown(battle, owner, BACKSTAB) == 1, "successful skill starts cooldown1");
        check(battle.quoteActorActionCost(owner, 15, true) == 19, "successful skill joins the shared25percent AP ramp");
        denied(BACKSTAB, SkillTarget.entity(enemy.getUUID()), "same-turn replay rejected without extra costs");
        check(TacticalSkills.available(battle, owner).stream().anyMatch(a -> a.id().equals(BACKSTAB) && !a.ready() && !a.reason().isBlank()),
            "unavailable owned skill remains visible with reason");
        owner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        check(!SkillSources.resolve(owner).containsKey(BACKSTAB) && TacticalSkills.cooldown(battle, owner, BACKSTAB) == 1,
            "unequip removes grant while retaining battle cooldown");
        owner.setItemSlot(EquipmentSlot.MAINHAND, granted(Items.IRON_SWORD, BACKSTAB, 1));
        denied(BACKSTAB, SkillTarget.entity(enemy.getUUID()), "hot swap does not erase cooldown");
        begin(enemy); check(TacticalSkills.cooldown(battle, owner, BACKSTAB) == 1, "another actor does not decrement owner cooldown");
        begin(owner); check(TacticalSkills.cooldown(battle, owner, BACKSTAB) == 0, "next actual own beginTurn makes cooldown1 ready");
        close(ManaResources.current(owner), mp - 10, "default battle recovery0 preserves remainder across own turns");
    }

    private static void recoveryCases() throws Exception {
        double before = ManaResources.current(owner); owner.getAttribute(ManaAttributes.MP_RECOVERY).setBaseValue(7);
        begin(enemy); close(ManaResources.current(owner), before, "other actor never recovers owner MP");
        begin(owner); close(ManaResources.current(owner), before + 7, "subsequent own turn adds native MP recovery attribute");
        friend.getAttribute(ManaAttributes.MP_RECOVERY).setBaseValue(7); ManaResources.set(friend, 12);
        begin(friend); close(ManaResources.current(friend), 12, "first real own turn skips positive MP recovery");
        begin(friend); close(ManaResources.current(friend), 19, "later friendly own turn recovers exactly7");
        owner.getAttribute(ManaAttributes.MAX_MP).setBaseValue(40); ManaResources.set(owner, 39);
        begin(owner); close(ManaResources.current(owner), 40, "own-turn MP recovery caps at live native maximum");
        owner.getAttribute(ManaAttributes.MAX_MP).setBaseValue(100); owner.getAttribute(ManaAttributes.MP_RECOVERY).setBaseValue(0);
        friend.getAttribute(ManaAttributes.MP_RECOVERY).setBaseValue(0);
    }

    private static void formulaAndAreaCases() throws Exception {
        persistent(MAGIC, 1); persistent(INVALID, 1); persistent(UNION, 1); persistent(LINE, 1); persistent(RECTANGLE, 1); persistent(TRIANGLE, 1);
        long revision = SkillRegistry.revision();
        try { SkillDefinition.builder(id("fixture_invalid_compile")).costs("unknown_fixture_variable", "0").build();
            throw new AssertionError("unknown formula unexpectedly compiled"); }
        catch (IllegalArgumentException expected) { check(SkillRegistry.revision() == revision, "invalid formula compile never publishes a partial registry"); }
        denied(INVALID, SkillTarget.entity(enemy.getUUID()), "nonfinite per-target formula fails preflight before resources/effects");
        denied(UNION, SkillTarget.ground(new GridPos(origin.getX() + 100, origin.getY(), origin.getZ()), 0), "outside battle ground anchor rejected");
        begin(owner); prepare(enemy); capture();
        check(TacticalSkills.cast(battle, owner, MAGIC, SkillTarget.entity(enemy.getUUID())), "native magic/effect skill succeeds");
        close(damage.getOrDefault(enemy.getUUID(), 0F), 4, "magic damage retains raw4 despite rear positioning");
        check(lastBreakdown == null, "magic source does not enter physical positional multiplier event");
        var effect = enemy.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
        check(effect != null && effect.getAmplifier() == 1 && effect.getDuration() == 400,
            "effect level+1 and duration+1 turn formulas become amplifier1 and400 actual ticks");
        place(enemy, -1, 2); place(second, 1, 2); place(friend, 0, 3); prepare(enemy); prepare(second); prepare(friend); capture();
        int ap = battle.currentAp(); double mp = ManaResources.current(owner);
        SkillTarget ground = SkillTarget.ground(battle.playerCell(), 0);
        SkillPreview preview = TacticalSkills.preview(battle, owner, UNION, ground);
        check(preview.valid() && Arrays.stream(preview.affected()).filter(i -> i == enemy.getId()).count() == 1
            && Arrays.stream(preview.affected()).filter(i -> i == second.getId()).count() == 1, "overlapping union deduplicates native target IDs");
        check(TacticalSkills.cast(battle, owner, UNION, ground), "overlapping native union executes");
        check(hits.getOrDefault(enemy.getUUID(), 0) == 1 && hits.getOrDefault(second.getUUID(), 0) == 1
            && hits.getOrDefault(friend.getUUID(), 0) == 0, "union damages each hostile once and excludes party");
        check(battle.currentAp() == ap, "zero AP shape action remains free"); close(ManaResources.current(owner), mp, "zero MP shape action remains free");
        place(enemy, -2, 0); place(second, 0, 3); place(friend, 5, 3);
        for (ResourceLocation shape : List.of(LINE, RECTANGLE, TRIANGLE)) {
            prepare(enemy); prepare(second); capture();
            float yaw = shape.equals(RECTANGLE) ? 0 : 90;
            check(TacticalSkills.cast(battle, owner, shape, SkillTarget.ground(battle.playerCell(), yaw)), "native shaped cast " + shape);
            check(hits.getOrDefault(enemy.getUUID(), 0) == 1 && hits.getOrDefault(second.getUUID(), 0) == 0,
                "offset/rotated shape selects actual hostile correctly " + shape);
        }
        extensionEnabled = true; prepare(enemy); capture();
        check(SkillSources.resolve(owner).get(EXTENSION) == 2, "third-party source supplies native cast level2");
        double attack = owner.getAttributeValue(Attributes.ATTACK_DAMAGE), armor = enemy.getArmorValue();
        check(TacticalSkills.cast(battle, owner, EXTENSION, SkillTarget.entity(enemy.getUUID())), "registered variable/attribute/action integration casts");
        close(damage.getOrDefault(enemy.getUUID(), 0F), 4 + attack + armor, "formula reads public numeric provider and actual actor/target attributes");
        check(actionCalls == 1, "custom action executes exactly once");
        extensionEnabled = false; check(!SkillSources.resolve(owner).containsKey(EXTENSION), "dynamic source removal affects current ownership");
    }

    private static void launchBow() throws Exception {
        begin(owner); prepare(second); place(second, 4, 0); setYaw(second, 90);
        owner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW)); owner.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 8));
        capture(); bowHealth = second.getHealth(); bowMp = ManaResources.current(owner);
        int ap = battle.currentAp(); battle.tryAttack(second.getUUID());
        check(battle.pendingProjectiles() > 0 && owner.getOffhandItem().getCount() == 7
            && battle.currentAp() == ap - 15 && battle.successfulPaidActions() == 1, "ordinary native bow creates actual arrow and pays only its15 AP");
    }

    private static void lifecycle() throws Exception {
        begin(owner); place(enemy, 0, 1); setYaw(enemy, 0); prepare(enemy);
        owner.setItemSlot(EquipmentSlot.MAINHAND, granted(Items.IRON_SWORD, BACKSTAB, 1)); owner.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        check(TacticalSkills.cast(battle, owner, BACKSTAB, SkillTarget.entity(enemy.getUUID())), "final native backstab establishes live cooldown before exit");
        double remaining = ManaResources.current(owner); check(TacticalSkills.cooldown(battle, owner, BACKSTAB) == 1, "cooldown exists before battle exit");
        BattleManager.reset(); close(ManaResources.current(owner), remaining, "battle end preserves persistent MP");
        check(BattleManager.start(owner, true), "same real player can reenter encounter"); battle = BattleManager.current();
        close(ManaResources.current(owner), remaining, "reentry never refills saved MP");
        check(TacticalSkills.cooldown(battle, owner, BACKSTAB) == 0, "new battle resets old cooldown");
    }

    private record Snapshot(int ap, double mp, int paid, int cooldown, Map<UUID, Float> health) {}
    private static Snapshot snapshot(ResourceLocation skill) {
        Map<UUID, Float> health = new HashMap<>();
        for (Entity entity : entities) if (entity instanceof LivingEntity actor) health.put(actor.getUUID(), actor.getHealth());
        return new Snapshot(battle.currentAp(), ManaResources.current(owner), battle.successfulPaidActions(), TacticalSkills.cooldown(battle, owner, skill), Map.copyOf(health));
    }
    private static void denied(ResourceLocation skill, SkillTarget target, String label) {
        Snapshot before = snapshot(skill);
        check(!TacticalSkills.cast(battle, owner, skill, target), label + " rejects");
        check(before.equals(snapshot(skill)), label + " preserves AP/MP/count/cooldown/all native health");
    }
    private static void capture() { hits.clear(); damage.clear(); lastBreakdown = null; }
    private static void persistent(ResourceLocation id, int level) {
        CompoundTag tag = owner.getPersistentData().getCompound("tactical"); ListTag list = tag.getList("skills", 10);
        list.add(grant(id, level)); tag.put("skills", list); owner.getPersistentData().put("tactical", tag);
    }
    private static CompoundTag grant(ResourceLocation id, int level) {
        CompoundTag tag = new CompoundTag(); tag.putString("id", id.toString()); tag.putInt("level", level); return tag;
    }
    private static ItemStack granted(net.minecraft.world.item.Item item, ResourceLocation id, int level) {
        ItemStack stack = new ItemStack(item); CompoundTag root = new CompoundTag(), tactical = new CompoundTag();
        ListTag skills = new ListTag(); skills.add(grant(id, level)); tactical.put("skills", skills); root.put("tactical", tactical);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root)); return stack;
    }
    private static void clearGrants() {
        owner.getPersistentData().remove("tactical"); owner.getPersistentData().remove("tactical:skills");
        for (EquipmentSlot slot : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET))
            owner.setItemSlot(slot, ItemStack.EMPTY);
    }
    private static void prepare(LivingEntity actor) {
        actor.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); actor.getAttribute(Attributes.ARMOR).setBaseValue(0);
        actor.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(0); actor.removeAllEffects(); actor.setHealth(1000);
        actor.setAbsorptionAmount(0); actor.invulnerableTime = 0; actor.hurtTime = 0; actor.clearFire();
        if (actor instanceof ServerPlayer player) { player.resetAttackStrengthTicker(); player.setInvulnerable(false); }
    }
    private static void setYaw(LivingEntity actor, float yaw) { actor.setYRot(yaw); actor.setYHeadRot(yaw); actor.setYBodyRot(yaw); }
    private static void place(LivingEntity actor, int x, int z) throws Exception {
        GridPos cell = new GridPos(origin.getX() + x, origin.getY(), origin.getZ() + z);
        actor.setPos(cell.x() + .5, cell.y(), cell.z() + .5); actor.setDeltaMovement(Vec3.ZERO);
        if (actor == owner) field(battle, "playerCell").set(battle, cell);
        else {
            Object state = ((Map<?, ?>) field(battle, "enemies").get(battle)).get(actor.getUUID());
            field(state, "cell").set(state, cell);
        }
    }
    private static Field field(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field; }
    private static void begin(LivingEntity actor) throws Exception {
        Object queue = field(battle, "queue").get(battle); field(queue, "current").set(queue, actor.getUUID().toString());
        var begin = ServerBattle.class.getDeclaredMethod("beginTurn", String.class); begin.setAccessible(true); begin.invoke(battle, actor.getUUID().toString());
    }
    private static void close(double actual, double expected, String label) { check(Math.abs(actual - expected) < .005, label + ": actual=" + actual + " expected=" + expected); }
    private static void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }

    private static void finish(MinecraftServer server) {
        enabled = cancelBefore = cancelAttack = extensionEnabled = false; BattleManager.reset();
        for (Entity entity : entities) {
            if (entity instanceof ServerPlayer player && !player.isRemoved()) world.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            else entity.discard();
        }
        entities.clear();
        for (Wire wire : wires) if (wire.channel != null) wire.channel.finishAndReleaseAll(); wires.clear();
        if (world != null) for (BlockPos chunk : chunks) world.setChunkForced(chunk.getX(), chunk.getZ(), false); chunks.clear();
        if (configured) {
            var config = TacticalConfig.INSTANCE; config.initialMp.set(oldInitial); config.outsideMpRecovery.set(oldOutside);
            config.outsideMpInterval.set(oldInterval); config.participantCap.set(oldCap); configured = false;
        }
        BattleManager.setAutoTrigger(true); BattleEffects.clear(); server.halt(false);
    }
    private static final class Wire extends Connection {
        EmbeddedChannel channel; int payloads;
        Wire() { super(PacketFlow.SERVERBOUND); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundCustomPayloadPacket) payloads++;
        }
    }
}
