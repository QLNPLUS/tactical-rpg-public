package com.tactical.neoforge.progression;

import com.mojang.authlib.GameProfile;
import com.tactical.core.progression.CharacterStat;
import com.tactical.core.progression.ExperienceCurve;
import com.tactical.core.progression.ProgressionReason;
import com.tactical.core.progression.ProgressionRules;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.battle.BattleManager;
import com.tactical.neoforge.network.CharacterStatePacket;
import com.tactical.neoforge.skill.ManaAttributes;
import com.tactical.neoforge.skill.ManaResources;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Disposable dedicated-server fixture for character growth: {@code -Dtactical.selftest=progression}.
 *
 * <p>It drives the real service, the real store and the real native attributes inside a booted server, so
 * what a plain unit test cannot reach is genuinely exercised: experience is spent through the server's own
 * fields with the whole-point wallet, the attributes are the live ones on a player (including foreign flat
 * and multiplied modifiers), the record is written into real persistent data, and the answers are the real
 * payloads - one of them encoded and decoded through the native component codec.</p>
 *
 * <p>It never launches a client. The single battle it opens exists only to prove that a fighter cannot
 * change levels or points; it is stopped and the battle registry reset before the fixture ends. Failure
 * halts the server with a non-zero exit code, so a green Gradle run can never look like a passing
 * fixture.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class ProgressionSelfTest {

    private static final ResourceLocation FOREIGN_FLAT =
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "selftest_foreign_flat");
    private static final ResourceLocation FOREIGN_MULTIPLY =
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "selftest_foreign_multiply");
    private static final ResourceLocation FOREIGN_ZERO_POWER =
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "selftest_foreign_zero_power");

    private static boolean enabled;
    private static int ticks;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static ServerPlayer other;
    private static Creeper enemy;
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<RecordingConnection> wires = new ArrayList<>();
    private static final List<BlockPos> chunks = new ArrayList<>();

    private ProgressionSelfTest() { }

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        enabled = "progression".equals(System.getProperty("tactical.selftest"));
        ticks = 0;
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (!enabled) return;
        try {
            if (++ticks == 20) setup(event.getServer());
            if (ticks < 40) return;
            run(event.getServer());
            Tactical.LOGGER.info("[Tactical] PROGRESSION SELFTEST PASSED: whole-point XP wallet at a segment "
                    + "boundary, mid-transaction rollback, foreign-modifier-exact preview, actual AP semantics, "
                    + "respected zero skill power, config-reload refresh, lowered-cap history, read-only states, "
                    + "in-battle growth on the own idle turn without any extra resource, waiting/busy/dead/"
                    + "spectator refusals, death clone and the real payload wire");
            finish(event.getServer());
        } catch (Exception | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] PROGRESSION SELFTEST FAILED", failure);
            finish(event.getServer());
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw new IllegalStateException(failure);
        }
    }

    private static void setup(MinecraftServer server) {
        BattleManager.setAutoTrigger(false);
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX(), Math.min(level.getMaxBuildHeight() - 8, spawn.getY() + 16), spawn.getZ());
        for (int x = (origin.getX() - 8) >> 4; x <= (origin.getX() + 8) >> 4; x++)
            for (int z = (origin.getZ() - 8) >> 4; z <= (origin.getZ() + 8) >> 4; z++)
                if (level.setChunkForced(x, z, true)) chunks.add(new BlockPos(x, 0, z));
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
            level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 0; y < 4; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        owner = player(server, "GrowthOwner", 0);
        other = player(server, "GrowthOther", -3);
        enemy = EntityType.CREEPER.create(level);
        check(enemy != null, "enemy created");
        enemy.setPos(origin.getX() + 3.5, origin.getY(), origin.getZ() + 0.5);
        enemy.setPersistenceRequired();
        enemy.setNoAi(true);
        level.addFreshEntity(enemy);
    }

    private static ServerPlayer player(MinecraftServer server, String name, int offset) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        RecordingConnection wire = new RecordingConnection();
        wire.channel = new EmbeddedChannel(wire);
        // The fake connection only accepts payloads whose channel was declared on it, so every channel this
        // fixture can make the encounter send has to be listed - not only the character one. Ending the
        // encounter releases member visuals, which is a player_step, and a missing channel turns that into
        // an UnsupportedOperationException in the middle of the fixture.
        for (String id : List.of("character_state", "battle_state", "battle_state_part", "battle_log",
                "player_step", "creeper_telegraph", "move_choice_offer", "join_battle_result",
                "nearby_battles"))
            ChannelAttributes.getOrCreateAdHocChannels(wire).add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        new ServerGamePacketListenerImpl(server, wire, player, CommonListenerCookie.createInitial(profile, false));
        player.setPos(origin.getX() + offset + 0.5, origin.getY(), origin.getZ() + 0.5);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.2);
        level.addNewPlayer(player);
        players.add(player);
        wires.add(wire);
        return player;
    }

    private static void run(MinecraftServer server) {
        check(ProgressionHandler.registered(), "progression registered exactly once");
        ProgressionRules rules = ProgressionSettings.rules();
        check(rules.maxLevel() == 100 && rules.statCap() == 99 && rules.pointsPerLevel() == 1,
                "the loaded rules are the approved defaults");

        // ---- a brand-new character --------------------------------------------------------------
        ProgressionStore.remove(owner);
        CharacterStatePacket fresh = ProgressionService.snapshot(owner, 1, true);
        check(fresh.accepted(), "a fresh snapshot is not a refusal");
        check(fresh.level() == 1 && fresh.granted() == 0 && fresh.unspent() == 0, "a new character is level 1 with 0 points");
        check(fresh.revision() >= 1L, "a stored profile starts at a non-zero revision");
        check(fresh.openScreen(), "the open intent is echoed");
        check(fresh.pointsPerLevel() == rules.pointsPerLevel() && fresh.nextLevelPoints() == rules.pointsPerLevel(),
                "the page is told how many points a level-up grants");
        for (CharacterStat stat : CharacterStat.values())
            check(fresh.display()[stat.index()] == CharacterStat.BASE, "a new character displays 10 in " + stat);
        check(ProgressionStore.present(owner), "the profile was written to persistent data");
        check(owner.getMaxHealth() == 20.0D, "the displayed baseline grants no max health");
        check(ProgressionEffects.skillPower(owner) == 1.0D, "an uninvested character has skill power exactly 1");
        check(ProgressionEffects.derived(owner)[CharacterStat.ENDURANCE.index()] == 150.0D,
                "the published endurance value is the actual maximum AP, not the internal percentage");

        // ---- R2: one whole-point wallet for display, affordability and the spend itself ----------
        setXp(owner, 15, (float) (35.0D / 37.0D));
        check(ExperienceCurrency.available(owner) == 350L, "35/37 of level 15 is exactly 350 XP, not 349");
        check(ExperienceCurrency.spend(owner, 350L) != null, "350 XP can be spent when exactly 350 are owned");
        check(ExperienceCurrency.available(owner) == 0L && owner.experienceLevel == 0, "spending everything lands on 0 XP");
        setXp(owner, 15, (float) (34.0D / 37.0D));
        check(ExperienceCurrency.available(owner) == 349L, "34/37 of level 15 is exactly 349 XP");
        ExperienceCurrency.State untouched = ExperienceCurrency.capture(owner);
        check(ExperienceCurrency.spend(owner, 350L) == null, "one XP short is refused");
        check(ExperienceCurrency.capture(owner).equals(untouched), "a refused spend leaves all three fields untouched");
        setXp(owner, 30, 0.0F);
        check(ExperienceCurrency.available(owner) == 1395L, "the segment start at level 30 is 1395 XP");
        long before = ExperienceCurrency.available(owner);
        check(ExperienceCurrency.spend(owner, 100L) != null, "a normal spend succeeds");
        check(ExperienceCurrency.available(owner) == before - 100L, "a spend moves the balance by exactly its cost");
        check(ExperienceCurrency.spend(owner, 100L) != null, "a second spend succeeds");
        check(ExperienceCurrency.available(owner) == before - 200L, "two spends never create or lose a point");
        setXp(owner, 16, 0.0F);
        check(ExperienceCurrency.available(owner) == 352L, "the segment start at level 16 is 352 XP");
        setXp(owner, 31, 0.0F);
        check(ExperienceCurrency.available(owner) == 1507L, "the segment start at level 31 is 1507 XP");

        // ---- not enough XP is refused, nothing is spent ------------------------------------------
        setXp(owner, 2, 0.0F);
        long poorBalance = ExperienceCurrency.available(owner);
        CharacterStatePacket poor = ProgressionService.upgrade(owner, 2, fresh.revision());
        check(poor.refusal() == ProgressionReason.INSUFFICIENT_XP, "a balance below the price is refused");
        check(ExperienceCurrency.available(owner) == poorBalance, "a refused level-up spends no XP");
        check(ProgressionStore.load(owner, rules).profile().level() == 1, "a refused level-up grants no level");

        // ---- an exact spend across a segment boundary --------------------------------------------
        setXp(owner, 30, 0.0F);
        long price = rules.xpToNextLevel(1);
        CharacterStatePacket bought = ProgressionService.upgrade(owner, 3, fresh.revision());
        check(bought.accepted(), "a level-up with enough XP is accepted");
        check(bought.level() == 2 && bought.unspent() == 1 && bought.granted() == 1, "one level grants exactly one point");
        check(ExperienceCurrency.available(owner) == 1395L - price, "exactly the configured price was spent");
        check(owner.experienceLevel == 29, "the vanilla level really moved (1395 - 100 lands on level 29)");
        check(bought.revision() == fresh.revision() + 1L, "the revision advanced exactly once");

        // ---- a stale confirmation cannot spend twice ---------------------------------------------
        long afterPurchase = ExperienceCurrency.available(owner);
        CharacterStatePacket duplicate = ProgressionService.upgrade(owner, 4, fresh.revision());
        check(duplicate.refusal() == ProgressionReason.STALE_REVISION, "the same revision cannot be confirmed twice");
        check(ExperienceCurrency.available(owner) == afterPurchase, "a stale confirmation spends no XP");
        check(ProgressionStore.load(owner, rules).profile().unspent() == 1, "a stale confirmation grants no point");

        // ---- allocation, live attributes and no free refill --------------------------------------
        owner.setHealth(5.0F);
        ManaResources.set(owner, 10.0D);
        int[] vigor = new int[CharacterStat.COUNT];
        vigor[CharacterStat.VIGOR.index()] = 1;
        // A preview may not touch the real player: check the real value before and after it.
        CharacterStatePacket draft = ProgressionService.preview(owner, 5, bought.revision(), vigor);
        check(draft.accepted() && draft.draftValid(), "a legal draft is previewed");
        check(draft.draftDerived()[CharacterStat.VIGOR.index()] == 22.0D, "the draft predicts the new max health");
        check(owner.getMaxHealth() == 20.0D, "a preview changes nothing on the real player");
        check(close(owner.getHealth(), 5.0D), "a preview does not heal the real player");
        CharacterStatePacket allocated = ProgressionService.allocate(owner, 6, bought.revision(), vigor);
        check(allocated.accepted(), "an allocation within the balance is accepted");
        check(allocated.unspent() == 0, "the allocated point left the unspent balance");
        check(allocated.invested()[CharacterStat.VIGOR.index()] == 1, "the invested point is stored");
        check(allocated.display()[CharacterStat.VIGOR.index()] == 11, "the displayed value follows the investment");
        check(owner.getMaxHealth() == 22.0D, "vigor adds real max health");
        check(close(owner.getHealth(), 5.0D), "raising the cap did not heal the player");
        check(allocated.derived()[CharacterStat.VIGOR.index()] == 22.0D, "the published derived value is the real one");

        // ---- the modifier is upserted, never stacked --------------------------------------------
        ProgressionHandler.refresh(owner, "selftest");
        ProgressionHandler.refresh(owner, "selftest");
        check(owner.getMaxHealth() == 22.0D, "repeated login/refresh never stacks the modifier");
        check(owner.getAttribute(Attributes.MAX_HEALTH).getModifiers().size() == 1,
                "exactly this mod's own max-health modifier is installed");

        // ---- a mind point raises max MP without refilling it ------------------------------------
        setXp(owner, 35, 0.0F);
        CharacterStatePacket second = ProgressionService.upgrade(owner, 7, allocated.revision());
        check(second.accepted(), "a second level-up is accepted");
        int[] mind = new int[CharacterStat.COUNT];
        mind[CharacterStat.MIND.index()] = 1;
        CharacterStatePacket mental = ProgressionService.allocate(owner, 8, second.revision(), mind);
        check(mental.accepted(), "the mind point is accepted");
        check(close(ManaAttributes.maximum(owner), 105.0D), "mind adds real max MP");
        check(close(ManaResources.current(owner), 10.0D), "raising max MP did not refill MP");
        check(mental.derived()[CharacterStat.MIND.index()] == 105.0D, "the published max MP is the real one");

        // ---- endurance: percentage modifier, actual AP published -------------------------------
        CharacterStatePacket third = ProgressionService.upgrade(owner, 9, mental.revision());
        check(third.accepted(), "a third level-up is accepted");
        int[] endurance = new int[CharacterStat.COUNT];
        endurance[CharacterStat.ENDURANCE.index()] = 1;
        CharacterStatePacket sturdy = ProgressionService.allocate(owner, 10, third.revision(), endurance);
        check(sturdy.accepted(), "the endurance point is accepted");
        check(close(owner.getAttributeValue(com.tactical.neoforge.battle.TacticalAttributes.MAX_AP), 102.0D),
                "the native modifier stays percentage points");
        check(sturdy.derived()[CharacterStat.ENDURANCE.index()] == 153.0D,
                "the page is shown the actual maximum AP 153, not the internal percentage 102");

        // ---- R4: a reloaded rule set refuses the old quote and reaches the live attributes ------
        CharacterStatePacket pricedBeforeChange = ProgressionService.snapshot(owner, 11, false);
        double defaultVigor = TacticalConfig.INSTANCE.progressionVigorHp.getDefault();
        long xpBeforeRuleChange = ExperienceCurrency.available(owner);
        int unspentBeforeRuleChange = pricedBeforeChange.unspent();
        try {
            // No event and no refresh yet: the first request that arrives is the one the old page priced,
            // and it must be refused as a rule change before the fingerprint is repaired.
            TacticalConfig.INSTANCE.progressionVigorHp.set(defaultVigor + 3);
            CharacterStatePacket stale = ProgressionService.upgrade(owner, 12, pricedBeforeChange.revision());
            check(stale.refusal() == ProgressionReason.RULES_CHANGED,
                    "a page priced under previous rules is refused as a rule change, never repriced");
            check(ExperienceCurrency.available(owner) == xpBeforeRuleChange, "a rule-change refusal spends no XP");
            check(stale.unspent() == unspentBeforeRuleChange, "a rule-change refusal grants no point");
            check(stale.revision() > pricedBeforeChange.revision(), "the answer carries the advanced revision");
            // The configuration listener is what re-derives an already invested character.
            ProgressionHandler.refresh(owner, "config");
            check(owner.getMaxHealth() == 20.0D + 5.0D,
                    "the new coefficient reached the live attribute without another allocation");
            check(ProgressionEffects.derived(owner)[CharacterStat.VIGOR.index()] == 25.0D,
                    "the published value follows the reloaded rules");
        } finally {
            TacticalConfig.INSTANCE.progressionVigorHp.set(defaultVigor);
        }
        ProgressionHandler.refresh(owner, "config");
        check(owner.getMaxHealth() == 22.0D, "restoring the coefficient restores the real attribute");

        // ---- R1: a lowered cap keeps history, and foreign modifiers are part of the preview ----
        int defaultCap = TacticalConfig.INSTANCE.progressionStatCap.getDefault().intValue();
        try {
            TacticalConfig.INSTANCE.progressionStatCap.set(CharacterStat.BASE + 1);
            ProgressionHandler.refresh(owner, "config");
            CharacterStatePacket overCap = ProgressionService.snapshot(owner, 13, false);
            check(overCap.invested()[CharacterStat.VIGOR.index()] == 1, "a lowered cap takes no point back");
            check(overCap.display()[CharacterStat.VIGOR.index()] == 11, "the historical points stay visible");
            CharacterStatePacket growOverCap = ProgressionService.upgrade(owner, 14, overCap.revision());
            check(growOverCap.accepted(), "a level-up is still possible with a lowered stat cap");
            CharacterStatePacket refusedGrowth = ProgressionService.allocate(owner, 15, growOverCap.revision(), vigor);
            check(refusedGrowth.refusal() == ProgressionReason.STAT_CAP, "the over-cap stat cannot grow further");
            int[] underCap = new int[CharacterStat.COUNT];
            underCap[CharacterStat.STRENGTH.index()] = 1;
            CharacterStatePacket otherStat = ProgressionService.allocate(owner, 16, growOverCap.revision(), underCap);
            check(otherStat.accepted(), "a stat that is still under the cap allocates normally");
            check(otherStat.refusal() == ProgressionReason.OK, "the legal allocation reports success");
        } finally {
            TacticalConfig.INSTANCE.progressionStatCap.set(defaultCap);
        }
        ProgressionHandler.refresh(owner, "config");

        // ---- R1: foreign flat and multiplied modifiers are honoured by the preview -------------
        owner.getAttribute(Attributes.MAX_HEALTH).addTransientModifier(new AttributeModifier(FOREIGN_FLAT, 3.0D,
                AttributeModifier.Operation.ADD_VALUE));
        owner.getAttribute(Attributes.MAX_HEALTH).addTransientModifier(new AttributeModifier(FOREIGN_MULTIPLY, 0.5D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        try {
            setXp(owner, 40, 0.0F);
            CharacterStatePacket beforeForeign = ProgressionService.snapshot(owner, 17, false);
            CharacterStatePacket foreignUpgrade = ProgressionService.upgrade(owner, 18, beforeForeign.revision());
            check(foreignUpgrade.accepted(), "a level-up is accepted with foreign modifiers present");
            int[] anotherVigor = new int[CharacterStat.COUNT];
            anotherVigor[CharacterStat.VIGOR.index()] = 1;
            CharacterStatePacket foreignDraft = ProgressionService.preview(owner, 19, foreignUpgrade.revision(), anotherVigor);
            check(foreignDraft.accepted() && foreignDraft.draftValid(), "the draft is previewed");
            double predicted = foreignDraft.draftDerived()[CharacterStat.VIGOR.index()];
            check(predicted == 40.5D, "the preview replays foreign flat and multiplied modifiers: " + predicted);
            double beforeReal = owner.getMaxHealth();
            CharacterStatePacket foreignCommit = ProgressionService.allocate(owner, 20, foreignUpgrade.revision(), anotherVigor);
            check(foreignCommit.accepted(), "the drafted allocation is accepted");
            check(close(owner.getMaxHealth(), predicted), "preview and real allocation agree exactly");
            check(beforeReal != predicted, "the preview really predicted a change");
            check(owner.getAttribute(Attributes.MAX_HEALTH).getModifiers().size() == 3,
                    "foreign modifiers are kept next to this mod's single one");
        } finally {
            owner.getAttribute(Attributes.MAX_HEALTH).removeModifier(FOREIGN_FLAT);
            owner.getAttribute(Attributes.MAX_HEALTH).removeModifier(FOREIGN_MULTIPLY);
        }

        // ---- R1: a legitimate zero skill power is respected ------------------------------------
        owner.getAttribute(ProgressionEffects.SKILL_POWER).addTransientModifier(new AttributeModifier(FOREIGN_ZERO_POWER,
                -1.0D, AttributeModifier.Operation.ADD_VALUE));
        try {
            check(ProgressionEffects.skillPower(owner) == 0.0D, "a foreign zero multiplier is not raised to 1");
        } finally {
            owner.getAttribute(ProgressionEffects.SKILL_POWER).removeModifier(FOREIGN_ZERO_POWER);
        }
        check(ProgressionEffects.skillPower(owner) == 1.0D, "removing the foreign modifier restores the neutral value");

        // ---- R3: a mid-transaction failure consumes nothing and leaves no half-applied bonus ---
        setXp(owner, 45, 0.0F);
        CharacterStatePacket beforeSpare = ProgressionService.snapshot(owner, 21, false);
        CharacterStatePacket spare = ProgressionService.upgrade(owner, 22, beforeSpare.revision());
        check(spare.accepted() && spare.unspent() == 1, "a spare point is granted for the rollback case");
        CharacterStatePacket healthy = spare;
        long xpBeforeFailure = ExperienceCurrency.available(owner);
        double healthBeforeFailure = owner.getMaxHealth();
        ManaResources.set(owner, 20.0D);
        int[] failedDraft = new int[CharacterStat.COUNT];
        failedDraft[CharacterStat.STRENGTH.index()] = 1;
        try {
            ProgressionService.saver = (player, profile, ruleSet) -> {
                throw new IllegalStateException("injected commit failure");
            };
            CharacterStatePacket failedAllocate = ProgressionService.allocate(owner, 23, healthy.revision(), failedDraft);
            check(failedAllocate.refusal() == ProgressionReason.SERVER_ERROR, "a failed commit is reported, not hidden");
            CharacterStatePacket afterFailedAllocate = ProgressionService.snapshot(owner, 24, false);
            check(afterFailedAllocate.unspent() == healthy.unspent(), "a failed allocation gives no point away");
            check(Arrays.equals(afterFailedAllocate.invested(), healthy.invested()),
                    "a failed allocation stores nothing");
            check(close(owner.getMaxHealth(), healthBeforeFailure), "a failed allocation leaves no half-applied bonus");
            check(owner.getAttribute(Attributes.MAX_HEALTH).getModifiers().size() == 1,
                    "a failed allocation leaves exactly this mod's modifier");
            CharacterStatePacket failedUpgrade = ProgressionService.upgrade(owner, 25, afterFailedAllocate.revision());
            check(failedUpgrade.refusal() == ProgressionReason.SERVER_ERROR, "a failed level-up commit is reported");
            check(ExperienceCurrency.available(owner) == xpBeforeFailure, "a failed level-up gives the XP back");
            check(ProgressionStore.load(owner, rules).profile().level() == healthy.level(), "a failed level-up grants no level");
        } finally {
            ProgressionService.saver = ProgressionStore::save;
        }

        // ---- R5: in battle, only the acting member with nothing in flight may change its character --------
        //
        // The user's decision is that both a level-up and a point confirmation are allowed during a fight.
        // What is still refused is everything that is not "my own turn, nothing in flight": a waiting member,
        // an action already running, a dead player and a spectator stay read-only. Every check below reads the
        // real encounter, the real stored profile and the real live attributes.
        CharacterStatePacket beforeBattle = ProgressionService.snapshot(owner, 40, false);
        check(beforeBattle.canMutate(), "outside a battle the page is editable");
        check(BattleManager.start(owner, true), "the fixture battle starts");
        com.tactical.neoforge.battle.ServerBattle battle = BattleManager.containing(owner);
        check(battle != null, "the founder really belongs to the encounter");
        check(battle.joinDirect(other) == com.tactical.neoforge.battle.multi.BattleEligibility.Denial.NONE,
                "the second player joins the same encounter");
        begin(battle, owner);
        check(battle.isActorTurn(owner), "the owner is the acting member of its own turn");
        check(!battle.isBusy(), "and nothing is in flight");

        // Enough experience for one more level, so the in-battle transaction is a real one.
        CharacterStatePacket ownTurn = ProgressionService.snapshot(owner, 41, false);
        if (ownTurn.xpAvailable() < ownTurn.xpToNextLevel()) {
            setXp(owner, ownTurn.level() + 1, 0.0F);
            ownTurn = ProgressionService.snapshot(owner, 41, false);
        }
        check(ownTurn.canMutate(), "the acting member may change its character in its own turn");
        check(ownTurn.refusal() == ProgressionReason.OK, "and the reason is not a refusal");
        long xpBeforeGrowth = ExperienceCurrency.available(owner);
        int pointsBeforeGrowth = ownTurn.unspent();
        float healthBefore = owner.getHealth();
        double mpBefore = ManaResources.current(owner);
        int apBefore = battle.currentAp(owner);
        double maxApBefore = owner.getAttributeValue(com.tactical.neoforge.battle.TacticalAttributes.MAX_AP);
        int paidActionsBefore = battle.successfulPaidActions(owner);
        long otherXpBefore = ExperienceCurrency.available(other);
        float otherHealthBefore = other.getHealth();
        int otherApBefore = battle.currentAp(other);

        CharacterStatePacket battleLevel = ProgressionService.upgrade(owner, 42, ownTurn.revision());
        check(battleLevel.accepted(), "a level-up in the own idle turn is accepted");
        check(ExperienceCurrency.available(owner) == xpBeforeGrowth - ownTurn.xpToNextLevel(),
                "exactly the configured price was spent");
        check(battleLevel.unspent() == pointsBeforeGrowth + 1, "one level grants exactly one point");
        check(close(owner.getHealth(), healthBefore), "a level-up does not heal the player in battle");
        check(battle.currentAp(owner) == apBefore, "a level-up does not touch AP");

        int battleEndurance = CharacterStat.ENDURANCE.index();
        int[] battleAllocation = new int[CharacterStat.COUNT];
        battleAllocation[battleEndurance] = 1;
        CharacterStatePacket battleSpent = ProgressionService.allocate(owner, 43, battleLevel.revision(), battleAllocation);
        check(battleSpent.accepted(), "a confirmation in the own idle turn is accepted");
        check(battleSpent.unspent() == battleLevel.unspent() - 1, "exactly one point left the balance");
        check(battleSpent.invested()[battleEndurance] == battleLevel.invested()[battleEndurance] + 1,
                "the invested point is stored");
        check(close(owner.getAttributeValue(com.tactical.neoforge.battle.TacticalAttributes.MAX_AP),
                maxApBefore + 2.0D), "the real max AP followed the point");
        check(battle.currentAp(owner) == apBefore, "raising the cap never refills the current AP");
        check(close(ManaResources.current(owner), mpBefore), "nor refills MP");
        check(close(owner.getHealth(), healthBefore), "nor heals");
        check(battle.isActorTurn(owner), "the transaction did not advance the turn");
        check(battle.successfulPaidActions(owner) == paidActionsBefore,
                "and did not count as a battle action");
        check(!battle.isBusy(), "still nothing in flight");
        check(ExperienceCurrency.available(other) == otherXpBefore, "the other member's experience is untouched");
        check(close(other.getHealth(), otherHealthBefore), "and so is its health");
        check(battle.currentAp(other) == otherApBefore, "and its AP");

        // A waiting member is refused: the turn belongs to somebody else.
        long revisionAfterGrowth = battleSpent.revision();
        begin(battle, other);
        CharacterStatePacket waiting = ProgressionService.upgrade(owner, 44, revisionAfterGrowth);
        check(waiting.refusal() == ProgressionReason.IN_BATTLE, "a waiting member cannot level up in battle");
        check(!waiting.canMutate(), "and the answer reports itself as read-only");
        check(ExperienceCurrency.available(owner) == xpBeforeGrowth - ownTurn.xpToNextLevel(),
                "the refusal spent nothing");
        CharacterStatePacket waitingView = ProgressionService.snapshot(owner, 45, false);
        check(waitingView.level() == battleLevel.level(), "viewing stays available while waiting");

        // An action already in flight is refused as well.
        begin(battle, owner);
        check(battle.tryMove(new com.tactical.core.grid.GridPos(origin.getX() + 1, origin.getY(), origin.getZ())),
                "a real move starts and owns the encounter");
        CharacterStatePacket busy = ProgressionService.upgrade(owner, 46, revisionAfterGrowth);
        check(busy.refusal() == ProgressionReason.IN_BATTLE, "an action in flight keeps the page read-only");
        BattleManager.reset();

        CharacterStatePacket otherFresh = ProgressionService.snapshot(other, 47, false);
        other.setHealth(0.0F);
        check(!other.isAlive(), "the fixture body is not alive");
        CharacterStatePacket dead = ProgressionService.upgrade(other, 44, otherFresh.revision());
        check(dead.refusal() == ProgressionReason.DEAD, "a dead player cannot level up");
        check(ProgressionService.snapshot(other, 45, false).canMutate() == false, "a dead player still sees a readable page");
        other.setHealth(20.0F);
        other.setGameMode(GameType.SPECTATOR);
        CharacterStatePacket spectator = ProgressionService.snapshot(other, 46, false);
        check(spectator.refusal() == ProgressionReason.SPECTATOR, "a spectator is refused");
        check(!spectator.canMutate(), "a spectator page is read-only");
        other.setGameMode(GameType.SURVIVAL);
        check(ProgressionService.snapshot(other, 47, false).refusal() == ProgressionReason.OK,
                "returning to survival restores the ability to change the character");

        // ---- another player's resources are never touched --------------------------------------
        CharacterStatePacket otherState = ProgressionService.snapshot(other, 48, false);
        check(otherState.level() == 1 && otherState.unspent() == 0, "the other player keeps a fresh character");
        check(ExperienceCurrency.available(other) == 0L, "the other player's XP was never touched");
        check(other.getMaxHealth() == 20.0D, "the other player has no borrowed modifier");

        // ---- a death clone keeps the profile and the real attributes ----------------------------
        CharacterStatePacket owned = ProgressionService.snapshot(owner, 49, false);
        ServerPlayer clone = cloneOf(server, owner);
        // NeoForge's own argument order is (new player, old player, wasDeath): the first argument is the
        // event's entity, the second its original. Passing them the other way round copies from the empty
        // clone into the original and the clone silently starts at level one.
        NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(clone, owner, true));
        ProgressionStore.Loaded cloned = ProgressionStore.load(clone, ProgressionSettings.rules());
        check(cloned.usable(), "the cloned player has a usable profile");
        check(cloned.profile().level() == owned.level(), "the clone keeps the character level");
        check(cloned.profile().unspent() == owned.unspent(), "the clone keeps the unspent points");
        check(Arrays.equals(cloned.profile().invested(), owned.invested()), "the clone keeps the invested points");
        check(clone.getMaxHealth() == owner.getMaxHealth(), "the clone carries the same real max health");
        check(ProgressionEffects.skillPower(clone) == ProgressionEffects.skillPower(owner), "the clone carries the same skill power");

        // ---- the real payload survives the native component codec -------------------------------
        CharacterStatePacket expected = ProgressionService.snapshot(owner, 50, true);
        CharacterStatePacket wire = roundTrip(server.registryAccess(), expected);
        check(wire.owner().equals(expected.owner()), "the wire keeps the owner");
        check(wire.requestId() == expected.requestId() && wire.openScreen(), "the wire keeps the request and the open intent");
        check(wire.revision() == expected.revision() && wire.level() == expected.level(), "the wire keeps revision and level");
        check(wire.pointsPerLevel() == expected.pointsPerLevel() && wire.nextLevelPoints() == expected.nextLevelPoints(),
                "the wire keeps the per-level point grant");
        check(Arrays.equals(wire.invested(), expected.invested()), "the wire keeps the invested array");
        check(Arrays.equals(wire.display(), expected.display()), "the wire keeps the displayed values");
        check(Arrays.equals(wire.derived(), expected.derived()), "the wire keeps the derived values");
        check(Arrays.equals(wire.coefficients(), expected.coefficients()), "the wire keeps the rule coefficients");
        check(wire.reasonCode().equals(expected.reasonCode()) && wire.accepted(), "the wire keeps the answer code");
        check(wire.reason().getString().equals(expected.reason().getString()), "the wire keeps the text structure");
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * Makes one member the real acting member of the encounter: the turn queue names it and its own turn really
     * starts, exactly as the turn flow starts it, so "my turn" is the encounter's own fact and not a flag this
     * fixture sets.
     */
    private static void begin(com.tactical.neoforge.battle.ServerBattle battle, ServerPlayer actor) {
        try {
            java.lang.reflect.Field queueField = battle.getClass().getDeclaredField("queue");
            queueField.setAccessible(true);
            Object queue = queueField.get(battle);
            java.lang.reflect.Field current = queue.getClass().getDeclaredField("current");
            current.setAccessible(true);
            current.set(queue, actor.getUUID().toString());
            java.lang.reflect.Method beginTurn = battle.getClass().getDeclaredMethod("beginTurn", String.class);
            beginTurn.setAccessible(true);
            beginTurn.invoke(battle, actor.getUUID().toString());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("the fixture cannot start a real turn", failure);
        }
    }

    private static void setXp(ServerPlayer player, int level, float progress) {
        player.setExperienceLevels(level);
        player.experienceProgress = progress;
        player.totalExperience = (int) ExperienceCurve.integerBalance(level, progress);
    }

    private static boolean close(double actual, double expected) { return Math.abs(actual - expected) < 0.01D; }

    private static ServerPlayer cloneOf(MinecraftServer server, ServerPlayer original) {
        GameProfile profile = new GameProfile(original.getUUID(), original.getGameProfile().getName());
        ServerPlayer clone = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        clone.setPos(original.getX(), original.getY(), original.getZ());
        return clone;
    }

    private static CharacterStatePacket roundTrip(RegistryAccess registries, CharacterStatePacket packet) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(256, 1 << 20),
                registries, net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
        try {
            CharacterStatePacket.STREAM_CODEC.encode(buffer, packet);
            return CharacterStatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    private static void finish(MinecraftServer server) {
        enabled = false;
        ProgressionService.saver = ProgressionStore::save;
        BattleManager.reset();
        if (enemy != null) enemy.discard();
        for (ServerPlayer player : players) if (!player.isRemoved()) level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        for (RecordingConnection wire : wires) wire.channel.finishAndReleaseAll();
        if (level != null) for (BlockPos chunk : chunks) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        players.clear();
        wires.clear();
        chunks.clear();
        server.halt(false);
    }

    private static final class RecordingConnection extends Connection {
        private EmbeddedChannel channel;
        private final List<CharacterStatePacket> states = new ArrayList<>();

        private RecordingConnection() { super(PacketFlow.SERVERBOUND); }

        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof CharacterStatePacket state)
                states.add(state);
        }
    }
}
