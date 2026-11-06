package com.tactical.neoforge.battle;

import com.tactical.neoforge.battle.multi.BattleEligibility;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Locale;

/**
 * One place for the wording this mod's server itself produces: journal rows, chat lines, command
 * feedback, the damage breakdown and the item-target refusal.
 *
 * <p>Every producer here emits <em>structure</em> - a translation key plus typed arguments - and the
 * client resolves it in its own language. Nothing is resolved on the server: there is no {@code
 * getString()} on the way out, no sentence assembled in one language and translated back later. Numbers
 * that need a fixed format are formatted here (argument side) with {@link Locale#ROOT}, so a template is
 * only ever {@code %s}/{@code %%} and never a printf conversion; a template therefore cannot shift its
 * digits or separator with the client's locale.</p>
 *
 * <p>Names are the one thing that stays text: a player profile name, a custom or third-party name has to
 * read as the name somebody typed, so it is kept literal (formatting codes and control characters
 * stripped) and never treated as a key. An unnamed vanilla mob keeps its registry key instead, so a row
 * about a mob that died or unloaded is still readable in every language.</p>
 *
 * <p>This class deliberately carries no protocol of its own: the keys travel inside components that
 * {@code BattleLogPacket}/{@code BattleStatePacket} already know how to encode.</p>
 */
public final class BattleText {

    /** Chat marker of this mod. A technical identifier, so it is the same in every language. */
    public static final String CHAT_PREFIX = "[Tactical] ";

    // ---- encounter rows -------------------------------------------------------------------

    public static final String KEY_BATTLE_STARTED = "tactical.journal.battle.started";
    public static final String KEY_BATTLE_JOIN = "tactical.journal.battle.join";
    public static final String KEY_BATTLE_LEAVE = "tactical.journal.battle.leave";
    public static final String KEY_BATTLE_HANDOVER = "tactical.journal.battle.handover";
    public static final String KEY_BATTLE_INVITE = "tactical.journal.battle.invite";
    public static final String KEY_BATTLE_FRIENDLY = "tactical.journal.battle.friendly";
    /** A hand-commanded companion joined; arguments are the companion and its controller. */
    public static final String KEY_BATTLE_COMPANION = "tactical.journal.battle.companion";
    /** A controlled companion left because its control record stopped being valid; argument is the unit. */
    public static final String KEY_BATTLE_COMPANION_RELEASED = "tactical.journal.battle.companion_released";
    public static final String KEY_ENEMY_JOINED = "tactical.journal.enemy.joined";
    public static final String KEY_ENEMY_JOIN_ROLLED_BACK = "tactical.journal.enemy.join_rolled_back";

    // ---- movement -------------------------------------------------------------------------

    public static final String KEY_MOVE_OFFER = "tactical.journal.move.offer";
    public static final String KEY_MOVE_CANCELLED = "tactical.journal.move.cancelled";
    public static final String KEY_MOVE_WALK = "tactical.journal.move.walk";
    public static final String KEY_MOVE_DROP = "tactical.journal.move.drop";
    /** A unit lost the block it stood on; the fall itself costs no AP and counts as no action. */
    public static final String KEY_MOVE_FALL_BEGIN = "tactical.journal.move.fall_begin";
    public static final String KEY_MOVE_FALL = "tactical.journal.move.fall";

    // ---- attacks --------------------------------------------------------------------------

    public static final String KEY_ATTACK_MELEE = "tactical.journal.attack.melee";
    public static final String KEY_ATTACK_RANGED = "tactical.journal.attack.ranged";
    public static final String KEY_ATTACK_ENEMY_MELEE = "tactical.journal.attack.enemy_melee";
    public static final String KEY_ATTACK_ENEMY_RANGED = "tactical.journal.attack.enemy_ranged";
    public static final String KEY_ATTACK_ENEMY_DRINK = "tactical.journal.attack.enemy_drink";
    public static final String KEY_ATTACK_ENEMY_THROW = "tactical.journal.attack.enemy_throw";
    /** One blaze salvo: a single paid main action however many fireballs it really fired. */
    public static final String KEY_ATTACK_ENEMY_FIREBALL = "tactical.journal.attack.enemy_fireball";
    /** One drowned trident throw: the entity's own native ranged attack, not a player throw. */
    public static final String KEY_ATTACK_ENEMY_TRIDENT = "tactical.journal.attack.enemy_trident";
    public static final String KEY_WEAPON_BOW = "tactical.journal.weapon.bow";
    public static final String KEY_WEAPON_CROSSBOW = "tactical.journal.weapon.crossbow";

    // ---- defence --------------------------------------------------------------------------

    public static final String KEY_BLOCK = "tactical.journal.block";
    public static final String KEY_BLOCK_SHIELD = "tactical.journal.block.shield";
    public static final String KEY_BLOCK_STANCE = "tactical.journal.block.stance";

    // ---- items ----------------------------------------------------------------------------

    public static final String KEY_ITEM_ON_TARGET = "tactical.journal.item.on_target";
    public static final String KEY_ITEM_SELF = "tactical.journal.item.self";

    // ---- enemy actions --------------------------------------------------------------------

    public static final String KEY_ENEMY_FLIGHT_BLOCKED = "tactical.journal.enemy.flight_blocked";
    /** A swim or shore step is no longer legal; the unwalked steps are given back and the turn ends. */
    public static final String KEY_ENEMY_SWIM_BLOCKED = "tactical.journal.enemy.swim_blocked";
    public static final String KEY_ENEMY_DIVE_MISSED = "tactical.journal.enemy.dive_missed";
    public static final String KEY_ENEMY_NO_SHOT = "tactical.journal.enemy.no_shot";
    /** The blaze's own charge started; the wall-clock number is the vanilla charge length. */
    public static final String KEY_ENEMY_FIREBALL_CHARGE = "tactical.journal.enemy.fireball_charge";
    /** The charge finished and the first fireball can leave; nothing is charged until it really does. */
    public static final String KEY_ENEMY_FIREBALL_READY = "tactical.journal.enemy.fireball_ready";
    /** A salvo refused before any fireball existed: nothing was paid. */
    public static final String KEY_ENEMY_NO_FIREBALL = "tactical.journal.enemy.no_fireball";
    public static final String KEY_ENEMY_RETARGET = "tactical.journal.enemy.retarget";
    public static final String KEY_ENEMY_ROCKET_UNSUPPORTED = "tactical.journal.enemy.rocket_unsupported";
    public static final String KEY_ENEMY_NO_THROW = "tactical.journal.enemy.no_throw";
    public static final String KEY_ENEMY_DRINK_REFUSED = "tactical.journal.enemy.drink_refused";
    /** A door-capable actor really broke one closed wooden door pair, for one action. */
    public static final String KEY_ENEMY_BREAK_DOOR = "tactical.journal.enemy.break_door";
    /** The door it planned to break was not breakable any more; nothing was changed or charged. */
    public static final String KEY_ENEMY_DOOR_REFUSED = "tactical.journal.enemy.door_refused";
    /** The route changed under the actor, so the unwalked steps were given back and the turn ended. */
    public static final String KEY_ENEMY_ROUTE_BLOCKED = "tactical.journal.enemy.route_blocked";
    /** The enderman really relocated through vanilla's own teleport entry, at its own price. */
    public static final String KEY_ENEMY_TELEPORT = "tactical.journal.enemy.teleport";
    /** No landing point, price or quota this turn: nothing moved and nothing was charged. */
    public static final String KEY_ENEMY_TELEPORT_REFUSED = "tactical.journal.enemy.teleport_refused";
    /** The enderman spent its one reaction of this cycle to leave the spot it was hit from. */
    public static final String KEY_ENEMY_TELEPORT_REACTION = "tactical.journal.enemy.teleport_reaction";
    /** Its own turn starts with one real native water/rain hit instead of wall-clock ticks. */
    public static final String KEY_ENEMY_WATER_CLOCK = "tactical.journal.enemy.water_clock";
    /** Prefix of the potion names a witch row carries as an argument, e.g. {@code tactical.brew.healing}. */
    public static final String BREW_PREFIX = "tactical.brew.";
    public static final String KEY_ENEMY_FLIGHT_MOVE = "tactical.journal.enemy.flight_move";
    public static final String KEY_ENEMY_MOVE = "tactical.journal.enemy.move";

    // ---- turn / operator ------------------------------------------------------------------

    public static final String KEY_TURN = "tactical.journal.turn";
    public static final String KEY_ADMIN_AP = "tactical.journal.action.admin_ap";

    // ---- refusals (journal category "reject"/"info") --------------------------------------

    public static final String KEY_AP_NOT_ENOUGH = "tactical.journal.ap_not_enough";
    public static final String KEY_REJECT_MOVE_TARGET = "tactical.journal.reject.move_target";
    public static final String KEY_REJECT_NO_ROUTE = "tactical.journal.reject.no_route";
    public static final String KEY_REJECT_PENDING_MOVE = "tactical.journal.reject.pending_move";
    public static final String KEY_REJECT_MOVE_STALE = "tactical.journal.reject.move_stale";
    public static final String KEY_REJECT_ROUTE_GONE = "tactical.journal.reject.route_gone";
    public static final String KEY_REJECT_ROUTE_COST = "tactical.journal.reject.route_cost";
    public static final String KEY_REJECT_MELEE_RANGE = "tactical.journal.reject.melee_range";
    public static final String KEY_REJECT_BLOCKED = "tactical.journal.reject.blocked";
    public static final String KEY_REJECT_MELEE_NO_EFFECT = "tactical.journal.reject.melee_no_effect";
    public static final String KEY_REJECT_OUT_OF_RANGE = "tactical.journal.reject.out_of_range";
    public static final String KEY_REJECT_NO_AMMO = "tactical.journal.reject.no_ammo";
    public static final String KEY_REJECT_NO_BALLISTIC = "tactical.journal.reject.no_ballistic";
    public static final String KEY_REJECT_SHOT_NOT_FIRED = "tactical.journal.reject.shot_not_fired";
    public static final String KEY_REJECT_DEFEND_AP = "tactical.journal.reject.defend_ap";
    /** The companion's adapter does not declare this order at all. */
    public static final String KEY_REJECT_COMPANION_ORDER = "tactical.journal.reject.companion_order";
    public static final String KEY_REJECT_DEFEND_FACING = "tactical.journal.reject.defend_facing";
    public static final String KEY_REJECT_HELD_ITEM_CHANGED = "tactical.journal.reject.held_item_changed";
    public static final String KEY_REJECT_PLACE_OUTSIDE = "tactical.journal.reject.place_outside";
    public static final String KEY_REJECT_BREAK_OUTSIDE = "tactical.journal.reject.break_outside";
    public static final String KEY_REJECT_BREAK_TARGET = "tactical.journal.reject.break_target";
    public static final String KEY_REJECT_BREAK_UNBREAKABLE = "tactical.journal.reject.break_unbreakable";
    public static final String KEY_REJECT_BREAK_TOOL = "tactical.journal.reject.break_tool";
    public static final String KEY_REJECT_BREAK_SIGHT = "tactical.journal.reject.break_sight";
    public static final String KEY_REJECT_BREAK_PROTECTED = "tactical.journal.reject.break_protected";
    public static final String KEY_REJECT_BREAK_NO_LANDING = "tactical.journal.reject.break_no_landing";
    /** A block in the main hand places; a stale break intent that arrives with one is refused. */
    public static final String KEY_REJECT_BREAK_HELD_BLOCK = "tactical.journal.reject.break_held_block";
    /** The hit the client reported is not a point on the face it named. */
    public static final String KEY_REJECT_PLACE_HIT = "tactical.journal.reject.place_hit";
    /** The cell the placement would really fill is not a legal battle cell. */
    public static final String KEY_REJECT_PLACE_DEST = "tactical.journal.reject.place_dest";
    public static final String KEY_REJECT_BUILD_LIMIT = "tactical.journal.reject.build_limit";
    /** The forced fall could not settle: this is an exception, never a successful landing. */
    public static final String KEY_FALL_ABANDONED = "tactical.journal.reject.fall_abandoned";
    public static final String KEY_REJECT_NO_ITEM = "tactical.journal.reject.no_item";
    public static final String KEY_REJECT_WEAPON_NOT_ITEM = "tactical.journal.reject.weapon_not_item";
    /** The item command never places: a block in hand belongs to the block command. */
    public static final String KEY_REJECT_ITEM_IS_BLOCK = "tactical.journal.reject.item_is_block";
    public static final String KEY_REJECT_TARGET_GONE = "tactical.journal.reject.target_gone";
    public static final String KEY_REJECT_MENU_ESCAPE = "tactical.journal.reject.menu_escape";
    public static final String KEY_REJECT_ITEM_CANCELLED = "tactical.journal.reject.item_cancelled";
    public static final String KEY_REJECT_ITEM_UNSUPPORTED = "tactical.journal.reject.item_unsupported";
    public static final String KEY_REJECT_ITEM_CANNOT_USE = "tactical.journal.reject.item_cannot_use";
    public static final String KEY_REJECT_FLEE_AP = "tactical.journal.reject.flee_ap";
    public static final String KEY_REJECT_PATH_BLOCKED = "tactical.journal.reject.path_blocked";
    public static final String KEY_INFO_NOT_A_BLOCK = "tactical.journal.info.not_a_block";
    public static final String KEY_INFO_TOO_FAR = "tactical.journal.info.too_far";
    public static final String KEY_INFO_CANNOT_PLACE = "tactical.journal.info.cannot_place";
    public static final String KEY_INFO_CANNOT_BREAK = "tactical.journal.info.cannot_break";
    public static final String KEY_INFO_FLEE_FAILED = "tactical.journal.info.flee_failed";

    // ---- damage breakdown -----------------------------------------------------------------

    public static final String KEY_DAMAGE_POSITION = "tactical.journal.damage.position";
    /**
     * One damage modifier, as its own fragment.
     *
     * <p>The breakdown row used to be one template with five slots, which forced every factor to be printed
     * even when it was neutral ({@code ×1.00}) - four times the noise for a hit that was only side-on. Each
     * modifier now travels as its own localizable fragment and a factor equal to one is simply not appended,
     * so the wording still comes from the language table while the row only names what really changed. The
     * formatted value is passed as an argument, exactly like before, so no template carries a conversion.</p>
     */
    public static final String KEY_DAMAGE_FACTOR_DIRECTION = "tactical.journal.damage.factor.direction";
    public static final String KEY_DAMAGE_FACTOR_HEIGHT = "tactical.journal.damage.factor.height";
    public static final String KEY_DAMAGE_FACTOR_SKILL = "tactical.journal.damage.factor.skill";
    public static final String KEY_DAMAGE_FACTOR_DEFENSE = "tactical.journal.damage.factor.defense";
    public static final String KEY_SECTOR_FRONT = "tactical.journal.damage.sector.front";
    public static final String KEY_SECTOR_SIDE = "tactical.journal.damage.sector.side";
    public static final String KEY_SECTOR_REAR = "tactical.journal.damage.sector.rear";
    public static final String KEY_SECTOR_UNKNOWN = "tactical.journal.damage.sector.unknown";

    // ---- item target refusal --------------------------------------------------------------

    public static final String KEY_ITEM_REFUSAL_DIMENSION = "tactical.item.refusal.dimension";
    public static final String KEY_ITEM_REFUSAL_TARGET_GONE = "tactical.item.refusal.target_gone";
    public static final String KEY_ITEM_REFUSAL_SELF = "tactical.item.refusal.self";
    public static final String KEY_ITEM_REFUSAL_OTHER_BATTLE = "tactical.item.refusal.other_battle";
    public static final String KEY_ITEM_REFUSAL_NOT_PARTICIPANT = "tactical.item.refusal.not_participant";
    public static final String KEY_ITEM_REFUSAL_TOO_FAR = "tactical.item.refusal.too_far";
    public static final String KEY_ITEM_REFUSAL_BLOCKED = "tactical.item.refusal.blocked";

    // ---- exit and end reasons -------------------------------------------------------------

    public static final String KEY_EXIT_COMMAND = "tactical.exit.by_command";
    public static final String KEY_EXIT_DISCONNECTED = "tactical.exit.disconnected";
    public static final String KEY_EXIT_FLED = "tactical.exit.fled";
    public static final String KEY_EXIT_DEFEATED = "tactical.exit.defeated";
    public static final String KEY_EXIT_REMOVED = "tactical.exit.removed";
    public static final String KEY_EXIT_LEVEL_CHANGED = "tactical.exit.level_changed";
    public static final String KEY_EXIT_RESPAWNED = "tactical.exit.respawned";
    public static final String KEY_END_STOPPED = "tactical.end.stopped";
    public static final String KEY_END_NO_PARTICIPANTS = "tactical.end.no_participants";
    public static final String KEY_END_ALL_PLAYERS_LEFT = "tactical.end.all_players_left";
    public static final String KEY_END_PATH_BLOCKED = "tactical.end.path_blocked";
    public static final String KEY_END_EMPTY_QUEUE = "tactical.end.empty_queue";
    public static final String KEY_END_ENEMIES_DEFEATED = "tactical.end.enemies_defeated";
    public static final String KEY_END_RESET = "tactical.end.reset";

    // ---- chat -----------------------------------------------------------------------------

    public static final String KEY_CHAT_NOT_IN_BATTLE = "tactical.chat.not_in_battle";
    public static final String KEY_CHAT_ALREADY_IN_BATTLE = "tactical.chat.already_in_battle";
    public static final String KEY_CHAT_CREATE_FAILED = "tactical.chat.create_failed";
    public static final String KEY_CHAT_CREATE_CONFLICT = "tactical.chat.create_conflict";
    public static final String KEY_CHAT_ZONE_CONFLICT = "tactical.chat.zone_conflict";
    public static final String KEY_CHAT_NO_HOSTILE_FORCED = "tactical.chat.no_hostile_forced";
    public static final String KEY_CHAT_NO_HOSTILE = "tactical.chat.no_hostile";
    public static final String KEY_CHAT_BATTLE_STARTED = "tactical.chat.battle_started";
    public static final String KEY_CHAT_START_FAILED = "tactical.chat.start_failed";
    public static final String KEY_CHAT_JOINED = "tactical.chat.joined";
    public static final String KEY_CHAT_DECLINED = "tactical.chat.declined";
    public static final String KEY_CHAT_MULTIPLE_INVITES_JOIN = "tactical.chat.multiple_invites_join";
    public static final String KEY_CHAT_MULTIPLE_INVITES_DECLINE = "tactical.chat.multiple_invites_decline";
    public static final String KEY_CHAT_FLEE_IMMUNITY = "tactical.chat.flee_immunity";
    public static final String KEY_CHAT_INVITE = "tactical.chat.invite";
    public static final String KEY_CHAT_ACCEPT = "tactical.chat.accept";
    public static final String KEY_CHAT_DECLINE = "tactical.chat.decline";
    public static final String KEY_CHAT_OUTSIDER_ATTACK = "tactical.chat.outsider_attack";

    // ---- commands -------------------------------------------------------------------------

    public static final String KEY_COMMAND_TARGET_NOT_PARTICIPANT = "tactical.command.target_not_participant";
    public static final String KEY_COMMAND_AP_RECEIPT = "tactical.command.ap_receipt";
    public static final String KEY_COMMAND_INVITED = "tactical.command.invited";
    public static final String KEY_COMMAND_LEFT = "tactical.command.left";
    public static final String KEY_COMMAND_NOT_IN_BATTLE = "tactical.command.not_in_battle";
    public static final String KEY_COMMAND_AUTO_ON = "tactical.command.auto_on";
    public static final String KEY_COMMAND_AUTO_OFF = "tactical.command.auto_off";
    public static final String KEY_COMMAND_STATUS_NONE = "tactical.command.status_none";
    public static final String KEY_COMMAND_STATUS_SINGLE = "tactical.command.status_single";
    public static final String KEY_COMMAND_STATUS_MULTIPLE = "tactical.command.status_multiple";

    private BattleText() {
    }

    // ---- composition ----------------------------------------------------------------------

    public static MutableComponent translatable(String key, Object... args) {
        return Component.translatable(key, args);
    }

    /** Untrusted or typed text kept as text: formatting codes and control characters removed. */
    public static Component literal(String text) {
        return Component.literal(bounded(text, 240));
    }

    /**
     * A participant's name as structure.
     *
     * <p>An unnamed vanilla mob keeps its registry translation key, so a row about a mob that unloaded
     * or died still resolves; anything somebody typed stays that text and is never a key.</p>
     */
    public static Component name(Entity entity) {
        if (entity == null) return Component.empty();
        Component raw = entity.getName();
        if (raw.getContents() instanceof TranslatableContents) return raw;
        return Component.literal(bounded(raw.getString(), 64));
    }

    /** A name that is only known as text (a player profile name, an operator-typed reason). */
    public static Component name(String name) {
        return name == null ? Component.empty() : Component.literal(bounded(name, 64));
    }

    /**
     * A live entity's own identity as native structure, never flattened to a string.
     *
     * <p>An unnamed vanilla mob keeps its registry key. A name somebody typed is a component of its own
     * and stays that component - including any nested structure it carries - so {@code getString()} is
     * never called on the way out and a name that happens to look like a key is still just text. The
     * budget is enforced by the wire frame itself: a name that cannot travel falls back to the entity
     * type's own registry key, and only an unmeasurable one to a neutral placeholder.</p>
     */
    public static Component entityName(Entity entity) {
        if (entity == null) return Component.empty();
        return fitted(entity.getName(), Component.translatable(entity.getType().getDescriptionId()));
    }

    /** A held item's own name as native structure; the empty hand is a key of its own. */
    public static Component itemName(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return translatable(KEY_EMPTY_HAND);
        return fitted(stack.getHoverName(), Component.translatable(stack.getItem().getDescriptionId()));
    }

    /**
     * The candidate as it will really travel, together with its deep copy.
     *
     * <p>The state snapshot must never fail to publish because one name is oversized, so every production
     * name - literal, custom or a translation key - is proved here by the real component frame: the same
     * writer the packet uses, on the caller's registry context, then the reader, which both enforces the
     * receiving limit and returns the copy that is actually stored. That copy is what the producer keeps,
     * so a later mutation of the original component or of its nested arguments cannot change a snapshot
     * that is already published.</p>
     *
     * <p>There is deliberately no "obviously fits" shortcut: a translation key can carry an arbitrarily
     * long fallback, and a style can carry click, hover, insertion or font data, so the only reliable
     * measure is the writer itself. A name that does not fit falls back to the caller's stable alternative
     * and never to a truncated name; with no registry context to measure with, a neutral placeholder is
     * used instead of an unproven (possibly huge) component.</p>
     */
    public static Component fitted(Component preferred, Component fallback) {
        net.minecraft.core.RegistryAccess contexts = registries();
        if (contexts == null) {
            reportUnmeasured();
            return Component.literal("?");
        }
        Component verified = preferred == null ? null
                : com.tactical.neoforge.network.BattleLogPacket.verifiedSnapshot(preferred, contexts);
        if (verified != null) return verified;
        Component alternative = fallback == null ? null
                : com.tactical.neoforge.network.BattleLogPacket.verifiedSnapshot(fallback, contexts);
        if (alternative != null) return alternative;
        return Component.literal("?");
    }

    /** One line per server, so a misbehaving producer cannot flood the log. */
    private static void reportUnmeasured() {
        if (unmeasuredReported) return;
        unmeasuredReported = true;
        com.tactical.neoforge.Tactical.LOGGER.warn("[Tactical] a battle state name could not be measured: "
                + "no registry context, a neutral placeholder was used");
    }

    private static boolean unmeasuredReported;

    private static net.minecraft.core.RegistryAccess registries() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.registryAccess();
    }

    /** One chat line of this mod: the literal marker, then the caller's own structure. */
    public static void chat(ServerPlayer player, Component body) {
        if (player == null || player.connection == null || body == null) return;
        player.sendSystemMessage(Component.literal(CHAT_PREFIX).append(body));
    }

    /** The body of one command reply; the command source adds its own routing. */
    public static Component commandLine(Component body) {
        return Component.literal(CHAT_PREFIX).append(body);
    }

    /** Fixed two-decimal number, formatted as an argument so no template carries a printf conversion. */
    public static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Whole-block distance, formatted on the argument side for the same reason. */
    public static String whole(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    public static Component itemRefusal(ItemTargetRule.Denial denial) {
        return switch (denial) {
            case NONE -> Component.empty();
            case DIFFERENT_DIMENSION -> translatable(KEY_ITEM_REFUSAL_DIMENSION);
            case TARGET_GONE -> translatable(KEY_ITEM_REFUSAL_TARGET_GONE);
            case SELF -> translatable(KEY_ITEM_REFUSAL_SELF);
            case OTHER_BATTLE -> translatable(KEY_ITEM_REFUSAL_OTHER_BATTLE);
            case NOT_PARTICIPANT -> translatable(KEY_ITEM_REFUSAL_NOT_PARTICIPANT);
            case TOO_FAR -> translatable(KEY_ITEM_REFUSAL_TOO_FAR);
            case BLOCKED -> translatable(KEY_ITEM_REFUSAL_BLOCKED);
        };
    }

    /**
     * One participation refusal as structure.
     *
     * <p>{@link BattleEligibility#describe} stays for the legacy string field of the join/nearby replies
     * and is a later step; new chat never goes through it, so no refusal is frozen into one language.</p>
     */
    public static Component denial(BattleEligibility.Denial denial) {
        if (denial == null) return translatable(KEY_DENIAL_UNRESOLVED);
        return switch (denial) {
            case NONE -> Component.empty();
            case SESSION_CLOSED -> translatable(KEY_DENIAL_SESSION_CLOSED);
            case OFFLINE -> translatable(KEY_DENIAL_OFFLINE);
            case DEAD -> translatable(KEY_DENIAL_DEAD);
            case SPECTATOR -> translatable(KEY_DENIAL_SPECTATOR);
            case DIFFERENT_DIMENSION -> translatable(KEY_DENIAL_DIFFERENT_DIMENSION);
            case OUTSIDE_ZONE -> translatable(KEY_DENIAL_OUTSIDE_ZONE);
            case ALREADY_MEMBER -> translatable(KEY_DENIAL_ALREADY_MEMBER);
            case BATTLE_FULL -> translatable(KEY_DENIAL_BATTLE_FULL);
            case NOT_MANAGER -> translatable(KEY_DENIAL_NOT_MANAGER);
            case SELF_INVITE -> translatable(KEY_DENIAL_SELF_INVITE);
            case NO_INVITE -> translatable(KEY_DENIAL_NO_INVITE);
            case INVITE_EXPIRED -> translatable(KEY_DENIAL_INVITE_EXPIRED);
            case SESSION_MISMATCH -> translatable(KEY_DENIAL_SESSION_MISMATCH);
            case NOT_A_SAFE_BOUNDARY -> translatable(KEY_DENIAL_NOT_A_SAFE_BOUNDARY);
            case OCCUPIED -> translatable(KEY_DENIAL_OCCUPIED);
            case UNRESOLVED -> translatable(KEY_DENIAL_UNRESOLVED);
        };
    }

    public static final String KEY_DENIAL_SESSION_CLOSED = "tactical.denial.session_closed";
    public static final String KEY_DENIAL_OFFLINE = "tactical.denial.offline";
    public static final String KEY_DENIAL_DEAD = "tactical.denial.dead";
    public static final String KEY_DENIAL_SPECTATOR = "tactical.denial.spectator";
    public static final String KEY_DENIAL_DIFFERENT_DIMENSION = "tactical.denial.different_dimension";
    public static final String KEY_DENIAL_OUTSIDE_ZONE = "tactical.denial.outside_zone";
    public static final String KEY_DENIAL_ALREADY_MEMBER = "tactical.denial.already_member";
    public static final String KEY_DENIAL_BATTLE_FULL = "tactical.denial.battle_full";
    public static final String KEY_DENIAL_NOT_MANAGER = "tactical.denial.not_manager";
    public static final String KEY_DENIAL_SELF_INVITE = "tactical.denial.self_invite";
    public static final String KEY_DENIAL_NO_INVITE = "tactical.denial.no_invite";
    public static final String KEY_DENIAL_INVITE_EXPIRED = "tactical.denial.invite_expired";
    public static final String KEY_DENIAL_SESSION_MISMATCH = "tactical.denial.session_mismatch";
    public static final String KEY_DENIAL_NOT_A_SAFE_BOUNDARY = "tactical.denial.not_a_safe_boundary";
    public static final String KEY_DENIAL_OCCUPIED = "tactical.denial.occupied";
    public static final String KEY_DENIAL_UNRESOLVED = "tactical.denial.unresolved";

    /**
     * The same refusal in the self-service list context.
     *
     * <p>The invitation wording ("accept again in a moment") does not fit a player who clicked "join" in
     * the nearby list, so that one status carries its own line for the list. Every other refusal is the
     * single authoritative text.</p>
     */
    public static final String KEY_LIST_REFUSAL_NOT_A_SAFE_BOUNDARY = "tactical.denial.not_a_safe_boundary_join";

    /** One refusal as structure, worded for the nearby list instead of an invitation. */
    public static Component listDenial(BattleEligibility.Denial denial) {
        if (denial == BattleEligibility.Denial.NOT_A_SAFE_BOUNDARY) {
            return translatable(KEY_LIST_REFUSAL_NOT_A_SAFE_BOUNDARY);
        }
        return denial(denial);
    }

    // ---- battle state names ---------------------------------------------------------------

    /** The actor's empty hand; a key of its own so no language is frozen into the snapshot. */
    public static final String KEY_EMPTY_HAND = "tactical.state.weapon.empty";
    /**
     * What a companion with an empty main hand fights with. It is a weapon-name placeholder, not a species
     * claim: a ranged companion reads "Natural · range N" from the same key.
     */
    public static final String KEY_NATURAL_WEAPON = "tactical.state.weapon.natural";

    /** The two built-in creeper events; anything else the AI notices stays in the server log. */
    public static final String KEY_AI_CREEPER_PREPARE = "tactical.journal.ai.creeper.prepare";
    public static final String KEY_AI_CREEPER_DETONATE = "tactical.journal.ai.creeper.detonate";

    // ---- exit reasons as components -------------------------------------------------------

    public static Component exitReason(Exit exit) {
        return switch (exit) {
            case COMMAND -> translatable(KEY_EXIT_COMMAND);
            case DISCONNECTED -> translatable(KEY_EXIT_DISCONNECTED);
            case FLED -> translatable(KEY_EXIT_FLED);
            case DEFEATED -> translatable(KEY_EXIT_DEFEATED);
            case REMOVED -> translatable(KEY_EXIT_REMOVED);
            case LEVEL_CHANGED -> translatable(KEY_EXIT_LEVEL_CHANGED);
            case RESPAWNED -> translatable(KEY_EXIT_RESPAWNED);
        };
    }

    public static Component endReason(End end) {
        return switch (end) {
            case STOPPED -> translatable(KEY_END_STOPPED);
            case NO_PARTICIPANTS -> translatable(KEY_END_NO_PARTICIPANTS);
            case ALL_PLAYERS_LEFT -> translatable(KEY_END_ALL_PLAYERS_LEFT);
            case PATH_BLOCKED -> translatable(KEY_END_PATH_BLOCKED);
            case EMPTY_QUEUE -> translatable(KEY_END_EMPTY_QUEUE);
            case ENEMIES_DEFEATED -> translatable(KEY_END_ENEMIES_DEFEATED);
            case RESET -> translatable(KEY_END_RESET);
        };
    }

    /** Why one member left; a stable code, so no wording is decided by the producer. */
    public enum Exit {
        COMMAND,
        DISCONNECTED,
        FLED,
        DEFEATED,
        REMOVED,
        LEVEL_CHANGED,
        RESPAWNED
    }

    /** Why the encounter ended; a stable code, so no wording is decided by the producer. */
    public enum End {
        STOPPED,
        NO_PARTICIPANTS,
        ALL_PLAYERS_LEFT,
        PATH_BLOCKED,
        EMPTY_QUEUE,
        ENEMIES_DEFEATED,
        RESET
    }

    // ---- internals ------------------------------------------------------------------------

    private static String bounded(String text, int limit) {
        if (text == null) return "";
        StringBuilder builder = new StringBuilder(Math.min(text.length(), limit));
        for (int offset = 0; offset < text.length() && builder.length() < limit;) {
            int point = text.codePointAt(offset);
            offset += Character.charCount(point);
            if (point == 0x00a7) {
                if (offset < text.length()) offset += Character.charCount(text.codePointAt(offset));
                continue;
            }
            if (Character.isISOControl(point)) point = ' ';
            if (builder.length() + Character.charCount(point) > limit) break;
            builder.appendCodePoint(point);
        }
        return builder.toString().strip();
    }
}
