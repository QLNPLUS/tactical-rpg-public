package com.tactical.neoforge.battle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import com.tactical.neoforge.network.BattleLogPacket;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Disposable dedicated-server fixture for the server's own wording: {@code -Dtactical.selftest=server_text}.
 *
 * <p>Two phases, both on the real server. The <b>language phase</b> (at {@code ServerStartedEvent}) resolves
 * this step's components against the two shipped language tables: every key exists in both, every code enum
 * has a key, the damage breakdown keeps its numbers while its wording changes, and a name that merely looks
 * like a key is never translated.</p>
 *
 * <p>The <b>producer phase</b> (staged over ticks, two real {@link ServerPlayer}s with recording connections
 * and no graphics client) drives the producers this step migrated: an invitation chat with its two buttons,
 * the invitation and join journal rows, a legal melee attack with its damage breakdown, an item refusal on a
 * bystander, a rejected defence facing, the {@code /tactical ap} receipt, a leave row that also carries the
 * management handover, and the end-of-battle row. Each check reads the component the server really published
 * or really sent as chat - nothing is resolved on the server and nothing is reconstructed by hand.</p>
 *
 * <p>The two-language part is a <em>same-runtime two-language resolver</em>, not two clients on one server:
 * the dedicated server only ships the vanilla {@code en_us} table, so a vanilla key reads the same in both
 * runs while this mod's own wording differs. A real two-client check belongs to a later batch.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class ServerTextSelfTest {

    private static final String OWNER_NAME = "TextOwner";
    private static final String GUEST_NAME = "TextGuest";
    private static final int SETUP_TICK = 20;
    private static final int TICK_DEADLINE = 1200;

    private static boolean enabled;
    private static int ticks;
    private static int stage;
    private static int checks;
    private static int failures;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static ServerPlayer guest;
    private static Creeper enemy;
    private static Chicken bystander;
    private static ServerBattle battle;
    private static UUID sessionId;
    private static int ownerApBeforeCommand;
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<RecordingConnection> wires = new ArrayList<>();
    private static final List<BlockPos> forced = new ArrayList<>();

    private ServerTextSelfTest() {
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!"server_text".equals(System.getProperty("tactical.selftest", ""))) return;
        enabled = true;
        ticks = 0;
        stage = 0;
        checks = 0;
        failures = 0;
        Tactical.LOGGER.info("[Tactical] SERVER_TEXT beginning: the server's own journal, chat, command and "
                + "refusal wording, resolved from the shipped language tables");
        Language original = Language.getInstance();
        try {
            languageChecks();
        } catch (Throwable failure) {
            Tactical.LOGGER.error("[Tactical] SERVER_TEXT SELFTEST FAILED: language phase crashed after {} checks",
                    checks, failure);
            enabled = false;
            halt(event.getServer(), 1);
            return;
        } finally {
            Language.inject(original);
        }
        if (failures != 0) {
            Tactical.LOGGER.error("[Tactical] SERVER_TEXT SELFTEST FAILED: {} of {} language checks failed",
                    failures, checks);
            enabled = false;
            halt(event.getServer(), 1);
            return;
        }
        Tactical.LOGGER.info("[Tactical] SERVER_TEXT language phase passed ({} checks); producer phase follows",
                checks);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!enabled) return;
        MinecraftServer server = event.getServer();
        try {
            if (++ticks < SETUP_TICK) return;
            require(ticks < TICK_DEADLINE, "the fixture reached its deadline in stage " + stage);
            if (stage == 0) {
                setup(server);
                stage = 1;
                return;
            }
            if (stage == 1) {
                // The invitation is already published; accept it so the join rows exist too.
                invitationChecks();
                BattleEligibility.Denial denial = BattleManager.accept(guest, sessionId);
                require(denial == BattleEligibility.Denial.NONE, "the invited guest joined: " + denial);
                stage = 2;
                return;
            }
            if (stage == 2) {
                if (!battle.isPlayerTurnNow()) return;
                joinChecks();
                actionChecks();
                commandChecks();
                stage = 3;
                return;
            }
            if (stage == 3) {
                if (battle == null || battle.finished()) return;
                handoverChecks();
                // Ending the encounter through the last defeat keeps a member on the roster, so the
                // FINISH snapshot really has a recipient; leaving last would publish it to nobody.
                enemy.kill();
                stage = 4;
                return;
            }
            if (stage == 4) {
                if (!battle.finished()) return;
                endChecks();
                stage = 5;
                return;
            }
            if (stage == 5) {
                if (failures != 0) {
                    Tactical.LOGGER.error("[Tactical] SERVER_TEXT SELFTEST FAILED: {} of {} checks failed",
                            failures, checks);
                    finish(server, 1);
                    return;
                }
                Tactical.LOGGER.info("[Tactical] SERVER_TEXT SELFTEST PASSED: all {} checks on the producer "
                        + "rows, the chat/command wording and the two-language resolver", checks);
                finish(server, 0);
            }
        } catch (Throwable failure) {
            Tactical.LOGGER.error("[Tactical] SERVER_TEXT SELFTEST FAILED in stage {} at tick {}: {}",
                    stage, ticks, failure.toString(), failure);
            failures++;
            finish(server, 1);
        }
    }

    // ---- phase 1: the shipped language tables ---------------------------------------------

    private static void languageChecks() {
        Map<String, String> english = langTable("en_us");
        Map<String, String> chinese = langTable("zh_cn");
        List<String> keys = serverTextKeys();

        check("both shipped tables carry every key this step's producers use", () -> {
            for (String key : keys) {
                require(english.containsKey(key), "en_us is missing " + key);
                require(chinese.containsKey(key), "zh_cn is missing " + key);
                require(!english.get(key).isBlank() && !chinese.get(key).isBlank(), key + " is blank");
            }
        });

        check("every item refusal code has its own translated wording", () -> {
            List<String> seen = new ArrayList<>();
            for (ItemTargetRule.Denial denial : ItemTargetRule.Denial.values()) {
                Component row = BattleText.itemRefusal(denial);
                if (denial == ItemTargetRule.Denial.NONE) {
                    require(row.getString().isEmpty(), "NONE must not produce a refusal");
                    continue;
                }
                String key = translatable(row).getKey();
                require(!seen.contains(key), "two item refusals share the key " + key);
                seen.add(key);
                String en = resolve(english, row);
                String zh = resolve(chinese, row);
                require(!en.equals(zh), key + " reads identically in both languages");
                require(!en.isBlank() && !zh.isBlank(), key + " resolved to nothing");
            }
        });

        check("every participation denial code has its own translated wording", () -> {
            List<String> seen = new ArrayList<>();
            for (BattleEligibility.Denial denial : BattleEligibility.Denial.values()) {
                Component row = BattleText.denial(denial);
                if (denial == BattleEligibility.Denial.NONE) continue;
                String key = translatable(row).getKey();
                require(!seen.contains(key), "two denials share the key " + key);
                seen.add(key);
                require(!resolve(english, row).equals(resolve(chinese, row)), key + " reads identically");
            }
        });

        check("every exit and end code has its own translated wording", () -> {
            List<String> seen = new ArrayList<>();
            for (BattleText.Exit exit : BattleText.Exit.values()) {
                String key = translatable(BattleText.exitReason(exit)).getKey();
                require(!seen.contains(key), "two exit codes share the key " + key);
                seen.add(key);
            }
            seen.clear();
            for (BattleText.End end : BattleText.End.values()) {
                String key = translatable(BattleText.endReason(end)).getKey();
                require(!seen.contains(key), "two end codes share the key " + key);
                seen.add(key);
            }
        });

        check("the damage breakdown keeps its numbers while the wording changes", () -> {
            Component row = PositionalCombat.summary(new TacticalDamageMultipliers.Breakdown(
                    com.tactical.core.combat.PositionalDamage.Sector.REAR, 1.5D, 1.25D, 1.0D), 0.5D);
            require(row != null, "a hit whose modifiers really changed still writes a breakdown");
            TranslatableContents contents = translatable(row);
            require(BattleText.KEY_DAMAGE_POSITION.equals(contents.getKey()),
                    "the breakdown is its own template, got " + contents.getKey());
            Object[] args = contents.getArgs();
            require(args.length == 1, "the breakdown names the sector and then appends modifiers, got "
                    + args.length + " arguments");
            String en = resolve(english, row);
            String zh = resolve(chinese, row);
            Tactical.LOGGER.info("[Tactical] SERVER_TEXT note: breakdown en='{}' zh='{}'", en, zh);
            require(!en.equals(zh), "the breakdown reads identically in both languages");
            // The neutral skill factor is gone; the three that changed are still there, with their digits.
            for (String number : List.of("1.50", "1.25", "0.50")) {
                require(en.contains(number), "the english breakdown lost " + number + ": " + en);
                require(zh.contains(number), "the chinese breakdown lost " + number + ": " + zh);
            }
            require(!en.contains("1.00") && !zh.contains("1.00"),
                    "a neutral modifier is left out of the row: " + en);
            require(!en.contains("·  ·") && !zh.contains("·  ·"), "no separator was left dangling: " + en);
            require(resolve(english, (Component) args[0]).equals("rear"), "the sector argument reads as a name");
        });

        check("a hit whose every modifier is neutral writes no breakdown at all", () -> {
            Component row = PositionalCombat.summary(new TacticalDamageMultipliers.Breakdown(
                    com.tactical.core.combat.PositionalDamage.Sector.FRONT, 1.0D, 1.0D, 1.0D), 1.0D);
            require(row == null, "an all-neutral hit leaves no empty breakdown row");
            Component zero = PositionalCombat.summary(new TacticalDamageMultipliers.Breakdown(
                    com.tactical.core.combat.PositionalDamage.Sector.FRONT, 0.0D, 1.0D, 1.0D), 1.0D);
            require(zero != null && resolve(english, zero).contains("0.00"),
                    "a factor of zero is not neutral and stays readable");
        });

        check("a percent sign stays a literal and not an argument", () -> {
            Component row = BattleText.translatable(BattleText.KEY_INFO_FLEE_FAILED, 55);
            String en = resolve(english, row);
            String zh = resolve(chinese, row);
            require(en.contains("55%") && !en.contains("%%"), "the english flee report is '" + en + "'");
            require(zh.contains("55%") && !zh.contains("%%"), "the chinese flee report is '" + zh + "'");
        });

        check("a missing name is empty rather than null", () -> {
            require(BattleText.name((String) null).getString().isEmpty(), "a missing name must be empty");
        });

        check("a literal name that looks like a key is never translated", () -> {
            Component name = BattleText.name(BattleText.KEY_BATTLE_JOIN);
            require(resolve(english, name).equals(BattleText.KEY_BATTLE_JOIN), "the english name was translated");
            require(resolve(chinese, name).equals(BattleText.KEY_BATTLE_JOIN), "the chinese name was translated");
        });

        check("a row made of several segments resolves completely in both languages", () -> {
            Component row = BattleText.translatable(BattleText.KEY_BATTLE_LEAVE,
                    BattleText.name(OWNER_NAME), BattleText.translatable(BattleText.KEY_EXIT_COMMAND))
                    .append(BattleText.translatable(BattleText.KEY_BATTLE_HANDOVER,
                            BattleText.name(GUEST_NAME)));
            String en = resolve(english, row);
            String zh = resolve(chinese, row);
            Tactical.LOGGER.info("[Tactical] SERVER_TEXT note: leave row en='{}' zh='{}'", en, zh);
            require(en.contains(OWNER_NAME) && en.contains(GUEST_NAME), "the english row lost a name: " + en);
            require(zh.contains(OWNER_NAME) && zh.contains(GUEST_NAME), "the chinese row lost a name: " + zh);
            require(en.contains("left by command"), "the english row lost its reason: " + en);
            require(zh.contains("离开战斗（命令）"), "the chinese row lost its reason: " + zh);
            require(en.contains("handed over") && zh.contains("管理权"), "the handover segment did not resolve");
        });
    }

    // ---- phase 2: the real producers ------------------------------------------------------

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        origin = level.getSharedSpawnPos();
        for (int cx = (origin.getX() - 24) >> 4; cx <= (origin.getX() + 24) >> 4; cx++) {
            for (int cz = (origin.getZ() - 24) >> 4; cz <= (origin.getZ() + 24) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) forced.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 0; dy < 5; dy++) {
                    level.setBlock(origin.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        owner = player(server, OWNER_NAME, 0, 0);
        guest = player(server, GUEST_NAME, 2, 0);
        // The actor is armed but holds no bow, so the melee branch is the one that runs.
        owner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        owner.getInventory().selected = 0;

        enemy = EntityType.CREEPER.create(level);
        require(enemy != null, "the fixture could not create the mob");
        enemy.setPos(origin.getX() + 1.5D, origin.getY(), origin.getZ() + 0.5D);
        enemy.setNoAi(true);
        enemy.setPersistenceRequired();
        require(level.addFreshEntity(enemy), "the mob was registered in the real world");

        bystander = EntityType.CHICKEN.create(level);
        require(bystander != null, "the fixture could not create the bystander");
        bystander.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 2.5D);
        bystander.setNoAi(true);
        bystander.setPersistenceRequired();
        require(level.addFreshEntity(bystander), "the bystander was registered in the real world");

        require(BattleManager.start(owner, true), "the encounter started for the founder");
        battle = BattleManager.findFor(owner.getUUID());
        require(battle != null, "the encounter is routed to its founder");
        sessionId = battle.sessionId();
        BattleEligibility.Denial denial = BattleManager.invite(owner, guest);
        require(denial == BattleEligibility.Denial.NONE, "the manager invited the guest: " + denial);
        Tactical.LOGGER.info("[Tactical] SERVER_TEXT note: encounter {} started with owner + creeper, guest "
                + "invited", sessionId);
    }

    private static ServerPlayer player(MinecraftServer server, String name, int dx, int dz) {
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes(("tactical-text-" + name).getBytes(StandardCharsets.UTF_8)), name);
        ServerPlayer created = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        RecordingConnection wire = new RecordingConnection();
        wire.channel = new EmbeddedChannel(wire);
        for (String id : List.of("attack_intent", "attack_range_request", "attack_range_snapshot",
                "battle_log", "battle_state", "creeper_telegraph", "defend", "end_turn", "end_turn_facing",
                "flee", "inventory_action", "join_battle_intent", "join_battle_result", "move_choice",
                "move_choice_offer", "move_intent", "nearby_battles", "nearby_battles_request",
                "open_inventory", "place_block", "player_step", "skill_cast", "skill_list_request",
                "skill_preview_request", "skill_preview_response", "skill_state", "swap_slots", "use_item")) {
            ChannelAttributes.getOrCreateAdHocChannels(wire).add(
                    ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(server, wire, created, CommonListenerCookie.createInitial(profile, false));
        created.setPos(origin.getX() + dx + 0.5D, origin.getY(), origin.getZ() + dz + 0.5D);
        created.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).setBaseValue(0.1D);
        created.setHealth(20.0F);
        level.addNewPlayer(created);
        players.add(created);
        wires.add(wire);
        return created;
    }

    private static void invitationChecks() {
        check("the invitation chat is keyed and keeps its two buttons", () -> {
            List<Component> chat = wires.get(1).chats();
            require(!chat.isEmpty(), "the guest received no chat line");
            Component line = chat.get(chat.size() - 1);
            // The segments are nested, so they are flattened in display order before being read.
            List<Component> parts = flatten(line);
            require(parts.size() == 6, "the invitation line has 6 segments, got " + parts.size());
            require(parts.get(0).getContents() instanceof PlainTextContents.LiteralContents literal
                            && BattleText.CHAT_PREFIX.equals(literal.text()),
                    "the line starts with the literal marker");
            TranslatableContents invite = translatable(parts.get(1));
            require(BattleText.KEY_CHAT_INVITE.equals(invite.getKey()), "the body is the keyed invitation");
            require(invite.getArgs().length == 1, "the invitation names its sender");
            Component accept = parts.get(3);
            Component decline = parts.get(5);
            require(BattleText.KEY_CHAT_ACCEPT.equals(translatable(accept).getKey()), "the accept label is keyed");
            require(BattleText.KEY_CHAT_DECLINE.equals(translatable(decline).getKey()), "the decline label is keyed");
            // The commands, their colours and their order are the contract a client clicks on.
            require(clickCommand(accept).equals("/tactical battle accept " + sessionId),
                    "the accept button still runs the session accept, got " + clickCommand(accept));
            require(clickCommand(decline).equals("/tactical battle decline " + sessionId),
                    "the decline button still runs the session decline, got " + clickCommand(decline));
            // Colours are compared by value: the style stores a TextColor, not the enum constant.
            require(TextColor.fromLegacyFormat(ChatFormatting.GREEN).equals(accept.getStyle().getColor()),
                    "the accept button keeps its green colour, got " + accept.getStyle().getColor());
            require(TextColor.fromLegacyFormat(ChatFormatting.RED).equals(decline.getStyle().getColor()),
                    "the decline button keeps its red colour, got " + decline.getStyle().getColor());
            String en = resolve(langTable("en_us"), line);
            String zh = resolve(langTable("zh_cn"), line);
            Tactical.LOGGER.info("[Tactical] SERVER_TEXT note: invitation en='{}' zh='{}'", en, zh);
            require(en.contains(OWNER_NAME) && zh.contains(OWNER_NAME), "the sender's name is in both readings");
            require(en.contains("[Accept]") && zh.contains("[接受]"), "the button labels read per language");
        });

        check("the invitation journal row carries both names as arguments", () -> {
            BattleLogPacket.Entry row = lastRow(owner, "battle", BattleText.KEY_BATTLE_INVITE);
            Object[] args = translatable(row.text()).getArgs();
            require(args.length == 2, "the invitation row takes two names, got " + args.length);
            require(textOf(args[0]).equals(OWNER_NAME), "the manager is the first argument");
            require(textOf(args[1]).equals(GUEST_NAME), "the invitee is the second argument");
            String en = resolve(langTable("en_us"), row.text());
            String zh = resolve(langTable("zh_cn"), row.text());
            require(en.contains("invited") && zh.contains("邀请"), "the row reads as an invitation");
            require(!containsCjk(en), "the english invitation row still carries CJK: " + en);
        });
    }

    private static void joinChecks() {
        check("the join row reaches the new member as structure", () -> {
            BattleLogPacket.Entry row = lastRow(guest, "battle", BattleText.KEY_BATTLE_JOIN);
            Object[] args = translatable(row.text()).getArgs();
            require(args.length == 1, "the join row names the member once, got " + args.length);
            require(textOf(args[0]).equals(GUEST_NAME), "the join row names the guest");
            String en = resolve(langTable("en_us"), row.text());
            String zh = resolve(langTable("zh_cn"), row.text());
            require(en.contains("joined") && zh.contains("加入"), "the join row reads as a join");
            require(en.contains(GUEST_NAME) && zh.contains(GUEST_NAME), "the member's name survives both readings");
            require(!containsCjk(en), "the english join row still carries CJK: " + en);
        });

        check("the join chat line is keyed", () -> {
            Component line = lastChatWith(wires.get(1), BattleText.KEY_CHAT_JOINED);
            require(line != null, "the guest received no join confirmation");
            String en = resolve(langTable("en_us"), line);
            require(en.contains("Joined the battle"), "the english join line lost its template: " + en);
        });
    }

    private static void actionChecks() {
        check("a legal melee attack is a keyed row with the actor and the mob as names", () -> {
            float healthBefore = enemy.getHealth();
            int apBefore = battle.currentAp();
            int actions = battle.successfulPaidActions();
            battle.tryAttack(enemy.getUUID());
            Tactical.LOGGER.info("[Tactical] SERVER_TEXT note: melee attempt ap {} -> {}, mob health {} -> {}",
                    apBefore, battle.currentAp(), healthBefore, enemy.getHealth());
            require(enemy.getHealth() < healthBefore, "the melee attack dealt no damage");
            require(battle.currentAp() < apBefore, "the melee attack spent no AP");
            require(battle.successfulPaidActions() == actions + 1, "the melee attack was not counted once");
            BattleLogPacket.Entry row = lastRow(owner, "attack", BattleText.KEY_ATTACK_MELEE);
            Object[] args = translatable(row.text()).getArgs();
            require(args.length == 2, "the melee row takes two names, got " + args.length);
            require(textOf(args[0]).equals(OWNER_NAME), "the attacker is named");
            require(args[1] instanceof Component, "the victim travels as structure");
            TranslatableContents victim = translatable((Component) args[1]);
            require("entity.minecraft.creeper".equals(victim.getKey()),
                    "the victim keeps its vanilla key, got " + victim.getKey());
            require(textOf(args[1]).equals("Creeper"), "the victim reads as its vanilla name");
            String en = resolve(langTable("en_us"), row.text());
            String zh = resolve(langTable("zh_cn"), row.text());
            require(en.contains("melee-attacked") && zh.contains("近战攻击"), "the row reads as a melee attack");
            require(!containsCjk(en), "the english melee row still carries CJK: " + en);
        });

        check("the damage breakdown of that hit only names the modifiers that changed", () -> {
            BattleLogPacket.Entry row = findRow(owner, "damage", BattleText.KEY_DAMAGE_POSITION);
            if (row == null) {
                // The real hit had no modifier worth showing, which is exactly the rule: no row is written
                // rather than a header followed by four "×1.00". The main damage row is asserted separately.
                BattleLogPacket.Entry damage = lastRow(owner, "damage", BattleJournal.KEY_DAMAGE);
                require(damage != null, "the main damage row is still written");
                return;
            }
            TranslatableContents contents = translatable(row.text());
            Object[] args = contents.getArgs();
            require(args.length == 1, "the header takes the sector only, got " + args.length);
            require(args[0] instanceof Component, "the sector is a nested component");
            TranslatableContents sector = translatable((Component) args[0]);
            require(sector.getKey().startsWith("tactical.journal.damage.sector."),
                    "the sector is keyed, got " + sector.getKey());
            List<Component> siblings = row.text().getSiblings();
            require(!siblings.isEmpty(), "a breakdown without modifiers is never published");
            require(siblings.size() % 2 == 0, "separators only ever sit between modifiers: " + siblings.size());
            for (int index = 0; index < siblings.size(); index++) {
                if (index % 2 == 0) {
                    require(textOf(siblings.get(index)).equals(" · "),
                            "a separator, not an empty slot, was published at " + index);
                    continue;
                }
                TranslatableContents factor = translatable(siblings.get(index));
                require(factor.getKey().startsWith("tactical.journal.damage.factor."),
                        "every modifier is its own keyed fragment, got " + factor.getKey());
                String number = textOf(factor.getArgs()[0]);
                require(number.matches("\\d+\\.\\d{2}"),
                        "modifier " + index + " must be a fixed two-decimal number, got '" + number + "'");
                require(!number.equals("1.00"), "a neutral modifier is left out, got '" + number + "'");
            }
            String en = resolve(langTable("en_us"), row.text());
            String zh = resolve(langTable("zh_cn"), row.text());
            require(en.contains("Damage modifiers") && zh.contains("伤害修正"), "the breakdown lost its template");
            require(!containsCjk(en), "the english breakdown still carries CJK: " + en);
            require(!en.contains("·  ·") && !zh.contains("·  ·"), "no separator was left dangling: " + en);
        });

        check("an item action on a bystander is refused by code and spends nothing", () -> {
            int ownerAp = battle.currentAp(owner);
            int guestAp = battle.currentAp(guest);
            float bystanderHealth = bystander.getHealth();
            BattleManager.useItem(owner, battle.stamp(), 0, bystander.getUUID());
            require(battle.currentAp(owner) == ownerAp, "the refused item action spent the actor's AP");
            require(battle.currentAp(guest) == guestAp, "the refused item action spent another member's AP");
            require(bystander.getHealth() == bystanderHealth, "the refused item action harmed the bystander");
            BattleLogPacket.Entry row = lastRow(owner, "reject", BattleText.KEY_ITEM_REFUSAL_NOT_PARTICIPANT);
            require(row != null, "the refusal was not recorded with its own code");
            String en = resolve(langTable("en_us"), row.text());
            String zh = resolve(langTable("zh_cn"), row.text());
            require(en.contains("not part of this battle") && zh.contains("不在本次战斗中"),
                    "the refusal reads as a participation refusal: " + en);
            require(!containsCjk(en), "the english refusal still carries CJK: " + en);
        });

        check("a rejected defence facing spends nothing and is a keyed row", () -> {
            int ap = battle.currentAp();
            battle.tryDefendFacing(17);
            require(battle.currentAp() == ap, "an invalid facing spent AP");
            BattleLogPacket.Entry row = lastRow(owner, "reject", BattleText.KEY_REJECT_DEFEND_FACING);
            require(row != null, "the invalid facing was not recorded with its own code");
            require(resolve(langTable("en_us"), row.text()).contains("Invalid facing"),
                    "the refusal reads as an invalid facing");
        });
    }

    private static void commandChecks() {
        check("the AP receipt keeps its technical format in both languages", () -> {
            ServerBattle live = BattleManager.findFor(owner.getUUID());
            require(live != null, "the encounter is still routed");
            ownerApBeforeCommand = live.currentAp();
            int result = execute(server(), "tactical ap add @s 3", owner);
            require(result == 1, "the AP command succeeded, got " + result);
            int after = live.currentAp();
            require(after >= ownerApBeforeCommand, "the AP command reduced the value");
            Component line = lastChatWith(wires.get(0), BattleText.KEY_COMMAND_AP_RECEIPT);
            require(line != null, "the command produced no receipt");
            String en = resolve(langTable("en_us"), line);
            String zh = resolve(langTable("zh_cn"), line);
            Tactical.LOGGER.info("[Tactical] SERVER_TEXT note: AP receipt en='{}' zh='{}'", en, zh);
            require(en.startsWith(BattleText.CHAT_PREFIX), "the receipt keeps the literal marker: " + en);
            require(en.matches("\\[Tactical\\] " + OWNER_NAME + " AP: -?\\d+ -> -?\\d+"),
                    "the english receipt changed format: " + en);
            require(zh.matches("\\[Tactical\\] " + OWNER_NAME + " AP: -?\\d+ -> -?\\d+"),
                    "the chinese receipt changed format: " + zh);
            require(en.contains(" -> " + after), "the receipt reports the authoritative value: " + en);
        });
    }

    private static void handoverChecks() {
        check("the leave row carries its reason and the handover as separate segments", () -> {
            require(BattleManager.leave(owner, BattleText.exitReason(BattleText.Exit.COMMAND)),
                    "the manager left the encounter");
            BattleLogPacket.Entry row = lastRow(guest, "battle", BattleText.KEY_BATTLE_LEAVE);
            TranslatableContents contents = translatable(row.text());
            Object[] args = contents.getArgs();
            require(args.length == 2, "the leave row names the member and the reason, got " + args.length);
            require(textOf(args[0]).equals(OWNER_NAME), "the leaving member is named");
            require(args[1] instanceof Component, "the reason travels as structure");
            TranslatableContents reason = translatable((Component) args[1]);
            require(BattleText.KEY_EXIT_COMMAND.equals(reason.getKey()),
                    "the reason is the command exit code, got " + reason.getKey());
            // The handover is appended, not concatenated into one sentence.
            String en = resolve(langTable("en_us"), row.text());
            String zh = resolve(langTable("zh_cn"), row.text());
            boolean handover = en.contains("management handed over") && zh.contains("管理权移交给");
            require(handover, "the handover segment did not resolve: en='" + en + "' zh='" + zh + "'");
            require(en.contains(GUEST_NAME) && zh.contains(GUEST_NAME), "the new manager is named in both readings");
            require(!containsCjk(en), "the english leave row still carries CJK: " + en);
        });
    }

    private static void endChecks() {
        check("the end of the encounter is a keyed reason and a FINISH snapshot", () -> {
            BattleLogPacket published = wireOf(guest).lastLog();
            require(published != null, "the guest received no journal snapshot");
            require(published.lifecycle() == BattleLogPacket.FINISH,
                    "the final snapshot is a FINISH, got " + published.lifecycle());
            BattleLogPacket.Entry row = published.history().get(published.history().size() - 1);
            TranslatableContents contents = translatable(row.text());
            require(BattleJournal.KEY_FINISH.equals(contents.getKey()),
                    "the last row is the finish template, got " + contents.getKey());
            Object reason = contents.getArgs()[0];
            require(reason instanceof Component, "the end reason travels as structure");
            TranslatableContents nested = translatable((Component) reason);
            require(BattleText.KEY_END_ENEMIES_DEFEATED.equals(nested.getKey()),
                    "the end reason is the last-defeat code, got " + nested.getKey());
            String en = resolve(langTable("en_us"), row.text());
            String zh = resolve(langTable("zh_cn"), row.text());
            Tactical.LOGGER.info("[Tactical] SERVER_TEXT note: finish row en='{}' zh='{}'", en, zh);
            require(en.contains("Battle ended") && zh.contains("战斗结束"), "the finish template did not resolve");
            require(en.contains("all enemies were defeated") && zh.contains("敌人已被全部击败"),
                    "the end reason did not resolve in both languages");
            require(!containsCjk(en), "the english finish row still carries CJK: " + en);
        });

        check("no published journal row of this encounter still carries CJK in english", () -> {
            Map<String, String> english = langTable("en_us");
            int inspected = 0;
            for (RecordingConnection wire : wires) {
                BattleLogPacket packet = wire.lastLog();
                if (packet == null) continue;
                for (BattleLogPacket.Entry entry : packet.history()) {
                    inspected++;
                    String en = resolve(english, entry.text());
                    require(!containsCjk(en), "row '" + entry.category() + "' still reads as chinese: " + en);
                    require(!en.contains("tactical."), "row '" + entry.category() + "' left a key unresolved: " + en);
                }
            }
            require(inspected > 0, "no journal row was inspected");
            Tactical.LOGGER.info("[Tactical] SERVER_TEXT note: {} published rows resolve without any CJK or "
                    + "unresolved key in en_us", inspected);
        });
    }

    // ---- helpers --------------------------------------------------------------------------

    private static void check(String name, Runnable body) {
        checks++;
        try {
            body.run();
            Tactical.LOGGER.info("[Tactical] SERVER_TEXT [{}] PASS {}", checks, name);
        } catch (Throwable failure) {
            failures++;
            Tactical.LOGGER.error("[Tactical] SERVER_TEXT [{}] FAIL {}: {}", checks, name, failure.toString());
        }
    }

    private static MinecraftServer server() {
        return level.getServer();
    }

    private static int execute(MinecraftServer server, String command, ServerPlayer player) {
        try {
            return server.getCommands().getDispatcher().execute(command,
                    player.createCommandSourceStack().withPermission(2));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new AssertionError("command '" + command + "' did not parse: " + failure.getMessage());
        }
    }

    private static String clickCommand(Component component) {
        ClickEvent event = component.getStyle().getClickEvent();
        if (event == null) throw new AssertionError("the button has no click event");
        if (event.getAction() != ClickEvent.Action.RUN_COMMAND) {
            throw new AssertionError("the button is no longer a RUN_COMMAND: " + event.getAction());
        }
        return event.getValue();
    }

    /** The most recent row of one category whose text is the given key. */
    private static BattleLogPacket.Entry lastRow(ServerPlayer recipient, String category, String key) {
        BattleLogPacket.Entry found = findRow(recipient, category, key);
        if (found != null) return found;
        throw new AssertionError("no '" + category + "' row with key " + key + " was published to "
                + recipient.getGameProfile().getName());
    }

    /** The most recent row of one category whose text is the given key, or null when it was never written. */
    private static BattleLogPacket.Entry findRow(ServerPlayer recipient, String category, String key) {
        BattleLogPacket packet = wireOf(recipient).lastLog();
        if (packet == null) {
            // The member may not have received this row: fall back to any snapshot this member saw.
            throw new AssertionError("no journal snapshot was published to " + recipient.getGameProfile().getName());
        }
        BattleLogPacket.Entry found = null;
        for (BattleLogPacket.Entry entry : packet.history()) {
            if (!category.equals(entry.category())) continue;
            if (!(entry.text().getContents() instanceof TranslatableContents contents)) continue;
            if (key.equals(contents.getKey())) found = entry;
        }
        return found;
    }

    private static Component lastChatWith(RecordingConnection wire, String key) {
        Component found = null;
        for (Component line : wire.chats()) {
            if (carriesKey(line, key)) found = line;
        }
        return found;
    }

    /** The component tree in display order: the component itself, then its siblings, recursively. */
    private static List<Component> flatten(Component component) {
        List<Component> parts = new ArrayList<>();
        parts.add(component);
        for (Component sibling : component.getSiblings()) parts.addAll(flatten(sibling));
        return parts;
    }

    /** True when the component tree contains a translation of this key. */
    private static boolean carriesKey(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents contents && key.equals(contents.getKey())) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (carriesKey(sibling, key)) return true;
        }
        return false;
    }

    private static RecordingConnection wireOf(ServerPlayer player) {
        for (int index = 0; index < players.size(); index++) {
            if (players.get(index) == player) return wires.get(index);
        }
        throw new AssertionError("no recorded connection for " + player.getGameProfile().getName());
    }

    private static boolean containsCjk(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (Character.UnicodeScript.of(text.charAt(index)) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    private static String textOf(Object argument) {
        if (argument instanceof Component component) return component.getString();
        return String.valueOf(argument);
    }

    private static TranslatableContents translatable(Component component) {
        if (!(component.getContents() instanceof TranslatableContents contents)) {
            throw new AssertionError("expected a translation, got " + component.getContents().getClass().getName());
        }
        return contents;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Stops the fixture server; a failure exits the JVM non-zero instead of leaving a green build. */
    private static void halt(MinecraftServer server, int code) {
        server.halt(false);
        if (code != 0) Runtime.getRuntime().halt(code);
    }

    private static List<String> serverTextKeys() {
        List<String> keys = new ArrayList<>();
        for (java.lang.reflect.Field field : BattleText.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            if (!field.getName().startsWith("KEY_")) continue;
            try {
                keys.add((String) field.get(null));
            } catch (IllegalAccessException failure) {
                throw new AssertionError("cannot read " + field.getName(), failure);
            }
        }
        keys.sort(null);
        return keys;
    }

    private static Map<String, String> langTable(String file) {
        Map<String, String> table = new LinkedHashMap<>(Language.getInstance().getLanguageData());
        String path = "/assets/tactical/lang/" + file + ".json";
        try (InputStream input = ServerTextSelfTest.class.getResourceAsStream(path)) {
            if (input == null) throw new AssertionError("missing language resource " + path);
            JsonObject json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                table.put(entry.getKey(), entry.getValue().getAsString());
            }
        } catch (IOException failure) {
            throw new AssertionError("unreadable language resource " + path, failure);
        }
        if (!table.containsKey(BattleText.KEY_BATTLE_JOIN)) {
            throw new AssertionError(path + " did not contribute this step's keys");
        }
        return table;
    }

    /** Resolves one component exactly as a display would: the language is swapped, the component is not. */
    private static String resolve(Map<String, String> table, Component text) {
        Language original = Language.getInstance();
        Language.inject(language(table));
        try {
            return text.getString();
        } finally {
            Language.inject(original);
        }
    }

    private static Language language(Map<String, String> table) {
        return new Language() {
            @Override
            public String getOrDefault(String key, String fallback) {
                return table.getOrDefault(key, fallback);
            }

            @Override
            public boolean has(String key) {
                return table.containsKey(key);
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return false;
            }

            @Override
            public FormattedCharSequence getVisualOrder(FormattedText text) {
                return sink -> false;
            }
        };
    }

    private static void finish(MinecraftServer server, int code) {
        enabled = false;
        cleanup();
        BattleManager.reset();
        if (level != null) {
            for (BlockPos chunk : forced) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        }
        forced.clear();
        for (RecordingConnection wire : wires) {
            if (wire.channel != null) wire.channel.finishAndReleaseAll();
        }
        BattleEffects.clear();
        server.halt(false);
        if (code != 0) Runtime.getRuntime().halt(code);
    }

    private static void cleanup() {
        if (enemy != null && !enemy.isRemoved()) enemy.discard();
        if (bystander != null && !bystander.isRemoved()) bystander.discard();
        if (level != null) {
            for (ServerPlayer player : players) {
                if (!player.isRemoved()) level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            }
        }
        players.clear();
    }

    /** Captures what a real connection would have received: journal snapshots and chat lines. */
    private static final class RecordingConnection extends Connection {
        private EmbeddedChannel channel;
        private final List<BattleLogPacket> logs = new ArrayList<>();
        private final List<Component> chats = new ArrayList<>();

        private RecordingConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof BattleLogPacket log) {
                logs.add(log);
            }
            if (packet instanceof ClientboundSystemChatPacket chat) {
                chats.add(chat.content());
            }
        }

        private BattleLogPacket lastLog() {
            return logs.isEmpty() ? null : logs.get(logs.size() - 1);
        }

        private List<Component> chats() {
            return chats;
        }
    }
}
